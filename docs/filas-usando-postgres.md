# Arquitetura de Filas com PostgreSQL (Substituto de Broker)

## 1. Objetivo

Este documento descreve uma arquitetura para usar PostgreSQL como substituto de provedor de filas em cenarios de processamento assincrono, com:

- Enfileiramento transacional no proprio banco
- Sinalizacao com LISTEN/NOTIFY para reduzir polling agressivo
- Consumo concorrente seguro com `FOR UPDATE SKIP LOCKED`
- Retry, backoff, DLQ e observabilidade

O foco e confiabilidade operacional e simplicidade de stack quando um broker dedicado nao e obrigatorio.

## 2. Quando usar Postgres como fila

Use quando:

1. A aplicacao ja depende fortemente de PostgreSQL.
2. O volume e moderado e previsivel.
3. A equipe quer reduzir componentes operacionais.
4. E importante manter transacao unica entre escrita de negocio e enfileiramento (outbox-like).

Evite quando:

1. O throughput exigido e muito alto e continuo.
2. Sao necessarias features nativas de broker (fan-out massivo, replay longo, stream retention complexa).
3. Ha muitos consumidores heterogeneos com necessidade de roteamento avancado.

## 3. Principios da Arquitetura

1. PostgreSQL e a fonte da verdade da fila.
2. NOTIFY e apenas wake-up de workers, nao transporte do payload.
3. Consumidor deve fazer claim atomico do trabalho.
4. Mensagem nunca e removida sem rastreabilidade.
5. Retry e DLQ sao explicitos no modelo de dados.

## 4. Componentes

### 4.1 Tabela de fila

Persistencia de mensagens, status, tentativas e agendamento de proxima execucao.

### 4.2 Produtor

Insere mensagem na fila na mesma transacao do evento de negocio.

### 4.3 Worker

Busca lotes prontos, faz claim com lock, processa, confirma sucesso ou agenda retry.

### 4.4 Notifier

Trigger no insert/update relevante que executa `pg_notify` para acordar workers rapidamente.

## 5. Modelo de Dados

```sql
CREATE TYPE queue_status AS ENUM ('PENDING', 'PROCESSING', 'RETRY', 'DONE', 'FAILED');

CREATE TABLE job_queue (
  id BIGSERIAL PRIMARY KEY,
  queue_name TEXT NOT NULL,
  dedup_key TEXT,
  payload JSONB NOT NULL,
  status queue_status NOT NULL DEFAULT 'PENDING',
  available_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  attempts INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 8,
  locked_at TIMESTAMPTZ,
  locked_by TEXT,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_job_queue_dedup
  ON job_queue (queue_name, dedup_key)
  WHERE dedup_key IS NOT NULL
    AND status IN ('PENDING', 'PROCESSING', 'RETRY');

CREATE INDEX idx_job_queue_pick
  ON job_queue (queue_name, status, available_at, id)
  WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX idx_job_queue_stuck
  ON job_queue (status, locked_at)
  WHERE status = 'PROCESSING';
```

## 6. Producao da Mensagem

Padrao recomendado:

1. Executar alteracao de negocio.
2. Inserir registro em `job_queue` na mesma transacao.
3. Commit unico.

Isso evita inconsistencias do tipo "negocio confirmado, mensagem perdida".

Exemplo:

```sql
BEGIN;

-- atualizacao de negocio
UPDATE appointments SET status = 'READY_TO_NOTIFY' WHERE id = 123;

-- enfileiramento
INSERT INTO job_queue (queue_name, dedup_key, payload)
VALUES (
  'notification.send',
  'appointment:123:reminder',
  jsonb_build_object('appointmentId', 123, 'channel', 'WHATSAPP')
)
ON CONFLICT DO NOTHING;

COMMIT;
```

## 7. Claim de Trabalho (consumo concorrente)

Padrao seguro para N workers concorrentes:

```sql
WITH cte AS (
  SELECT id
  FROM job_queue
  WHERE queue_name = $1
    AND status IN ('PENDING', 'RETRY')
    AND available_at <= NOW()
  ORDER BY available_at, id
  FOR UPDATE SKIP LOCKED
  LIMIT $2
)
UPDATE job_queue q
SET status = 'PROCESSING',
    locked_at = NOW(),
    locked_by = $3,
    updated_at = NOW()
FROM cte
WHERE q.id = cte.id
RETURNING q.id, q.payload, q.attempts, q.max_attempts;
```

Esse padrao evita que dois workers processem a mesma mensagem ao mesmo tempo.

## 8. Confirmacao, Retry e DLQ

### 8.1 Sucesso

```sql
UPDATE job_queue
SET status = 'DONE',
    updated_at = NOW()
WHERE id = $1
  AND status = 'PROCESSING'
  AND locked_by = $2;
```

### 8.2 Falha com retry exponencial

Backoff sugerido: $delay = min(2^attempts, 900)$ segundos.

```sql
UPDATE job_queue
SET attempts = attempts + 1,
    status = CASE
      WHEN attempts + 1 >= max_attempts THEN 'FAILED'
      ELSE 'RETRY'
    END,
    available_at = CASE
      WHEN attempts + 1 >= max_attempts THEN available_at
      ELSE NOW() + make_interval(secs => LEAST((2 ^ (attempts + 1))::int, 900))
    END,
    last_error = $2,
    locked_at = NULL,
    locked_by = NULL,
    updated_at = NOW()
WHERE id = $1
  AND status = 'PROCESSING';
```

### 8.3 Dead Letter Queue

`status = 'FAILED'` representa DLQ logica.

Opcionalmente, use tabela separada para historico de DLQ:

```sql
CREATE TABLE job_queue_dlq (
  dlq_id BIGSERIAL PRIMARY KEY,
  original_job_id BIGINT NOT NULL,
  queue_name TEXT NOT NULL,
  payload JSONB NOT NULL,
  attempts INT NOT NULL,
  error TEXT,
  failed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

## 9. LISTEN/NOTIFY para wake-up

Trigger para acordar workers quando houver trabalho novo:

```sql
CREATE OR REPLACE FUNCTION notify_job_queue()
RETURNS trigger AS $$
BEGIN
  PERFORM pg_notify('job_queue_new', NEW.queue_name);
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_notify_job_queue ON job_queue;

CREATE TRIGGER trg_notify_job_queue
AFTER INSERT ON job_queue
FOR EACH ROW
EXECUTE FUNCTION notify_job_queue();
```

Regras importantes:

- NOTIFY nao substitui a consulta no banco
- Worker sempre deve consultar a tabela com claim atomico
- Em perda de notificacao, polling leve periodico (fallback) garante progresso

## 10. Recuperacao e Reconciliacao

### 10.1 Mensagens presas em PROCESSING

Se worker cair no meio do processamento, executar job de reconciliacao:

```sql
UPDATE job_queue
SET status = 'RETRY',
    locked_at = NULL,
    locked_by = NULL,
    available_at = NOW(),
    updated_at = NOW()
WHERE status = 'PROCESSING'
  AND locked_at < NOW() - INTERVAL '10 minutes';
```

### 10.2 Idempotencia no consumidor

Mesmo com claim seguro, o processamento deve ser idempotente (por `dedup_key` ou `job_id`) para proteger contra reexecucao apos falhas.

## 11. Escalabilidade

### 11.1 Vertical

Escala bem para volume moderado, desde que:

- indices estejam corretos
- queries de claim sejam curtas
- lotes tenham tamanho controlado

### 11.2 Horizontal

Adicionar workers concorrentes e ajustar:

- `LIMIT` de claim por worker
- numero de conexoes no pool
- taxa de polling fallback

Quando throughput crescer alem do razoavel para um unico Postgres, migrar para broker dedicado sem quebrar contrato de payload.

## 12. Observabilidade

Metricas minimas:

- `queue_jobs_pending`
- `queue_jobs_processing`
- `queue_jobs_retry`
- `queue_jobs_failed`
- `queue_claim_latency_ms`
- `queue_processing_latency_ms`
- `queue_retries_total`
- `queue_dlq_total`

Consultas operacionais uteis:

```sql
SELECT status, count(*)
FROM job_queue
GROUP BY status;

SELECT queue_name, count(*)
FROM job_queue
WHERE status IN ('PENDING', 'RETRY')
  AND available_at <= NOW()
GROUP BY queue_name;
```

Alertas sugeridos:

1. Backlog acima de limiar por mais de N minutos.
2. Crescimento acelerado de `FAILED`.
3. Jobs em `PROCESSING` alem do timeout.

## 13. Seguranca

1. Payload sem dados sensiveis desnecessarios.
2. Criptografar campos sensiveis quando inevitavel.
3. Separar roles SQL de produtor e consumidor.
4. Aplicar TLS nas conexoes de aplicacao com banco.
5. Auditar acessos administrativos de reprocessamento.

## 14. Implementacao de Referencia (esqueleto)

```text
startup worker:
  - abrir conexao dedicada para LISTEN job_queue_new
  - iniciar loop de claim/process/ack
  - iniciar polling fallback a cada X segundos

loop worker:
  - claim de lote com FOR UPDATE SKIP LOCKED
  - para cada job: processar idempotente
  - sucesso: DONE
  - falha transitoria: RETRY com backoff
  - falha permanente: FAILED (DLQ)

housekeeping:
  - reconciliar PROCESSING expirado
  - expurgo de DONE antigos por politica
  - dashboard e alertas
```

## 15. Trade-offs

Vantagens:

- Menos componentes de infraestrutura
- Transacao unica entre negocio e fila
- Operacao simples para cenarios moderados

Custos:

- Banco passa a concentrar mais carga
- Menos recursos nativos de roteamento que brokers dedicados
- Requer disciplina de tuning SQL e housekeeping

## 16. Checklist de Producao

1. Tabela de fila com indices e deduplicacao criada.
2. Claim com `FOR UPDATE SKIP LOCKED` validado.
3. Retry com backoff e DLQ implementados.
4. Reconciliacao de jobs presos ativa.
5. LISTEN/NOTIFY configurado como wake-up, com polling fallback.
6. Idempotencia do consumidor testada.
7. Dashboards e alertas operacionais publicados.
8. Testes de carga e falha executados (kill de worker, perda de conexao, pico).

## 17. Conclusao

PostgreSQL pode substituir um provedor de filas com robustez quando a arquitetura adota claim atomico, idempotencia, retry controlado, DLQ e observabilidade. LISTEN/NOTIFY funciona como acelerador de wake-up; a garantia real de entrega vem da tabela de fila e do protocolo de consumo.
