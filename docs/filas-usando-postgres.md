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

O schema é composto por quatro tabelas, criadas em duas migrações:

### 5.1 V001 — Fila, workers e histórico

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
  job_version BIGINT NOT NULL DEFAULT 1,   -- versao incrementada a cada transicao de status
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- claim concorrente (global)
CREATE INDEX idx_job_queue_pick
  ON job_queue (status, available_at, id)
  WHERE status IN ('PENDING', 'RETRY');

-- claim concorrente por nome de fila
CREATE INDEX idx_job_queue_queue_pick
  ON job_queue (queue_name, status, available_at, id)
  WHERE status IN ('PENDING', 'RETRY');

-- reconciliacao de jobs travados
CREATE INDEX idx_job_queue_stuck
  ON job_queue (status, locked_at)
  WHERE status = 'PROCESSING';

-- versionamento para publicacao de eventos
CREATE INDEX idx_job_queue_version
  ON job_queue (id, job_version);

-- deduplicacao de jobs pendentes/em processamento
CREATE UNIQUE INDEX uq_job_queue_dedup
  ON job_queue (queue_name, dedup_key)
  WHERE dedup_key IS NOT NULL
    AND status IN ('PENDING', 'PROCESSING', 'RETRY');

-- instancias de workers ativas e seus contadores
CREATE TABLE worker_registry (
  worker_id TEXT PRIMARY KEY,
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_heartbeat_at TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL,
  processed_count BIGINT NOT NULL DEFAULT 0,
  failed_count BIGINT NOT NULL DEFAULT 0
);

-- historico completo de execucoes por job
CREATE TABLE job_execution_history (
  execution_id BIGSERIAL PRIMARY KEY,
  job_id BIGINT NOT NULL REFERENCES job_queue (id) ON DELETE CASCADE,
  worker_id TEXT NOT NULL,
  attempt_number INT NOT NULL,
  outcome TEXT NOT NULL,
  error_message TEXT,
  started_at TIMESTAMPTZ NOT NULL,
  finished_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_job_execution_history_job_id
  ON job_execution_history (job_id, started_at DESC);
```

### 5.2 V002 — Outbox de eventos

A tabela `event_outbox` desacopla a publicação de eventos em tempo real do processamento dos jobs. O conteúdo e propósito desta tabela estão descritos na seção 11.

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

Dois triggers disparam `pg_notify('job_queue_new', queue_name)` — um no INSERT de novos jobs, outro no UPDATE quando um job volta a ficar disponível (status `PENDING` ou `RETRY` com `available_at <= NOW()`):

```sql
CREATE OR REPLACE FUNCTION notify_job_queue()
RETURNS trigger AS $$
BEGIN
  IF NEW.status IN ('PENDING', 'RETRY') THEN
    PERFORM pg_notify('job_queue_new', NEW.queue_name);
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- acorda workers ao enfileirar novos jobs
CREATE TRIGGER trg_notify_job_queue_insert
AFTER INSERT ON job_queue
FOR EACH ROW
EXECUTE FUNCTION notify_job_queue();

-- acorda workers quando um job em retry se torna disponivel
CREATE TRIGGER trg_notify_job_queue_retry
AFTER UPDATE OF status, available_at ON job_queue
FOR EACH ROW
WHEN (NEW.status IN ('PENDING', 'RETRY') AND NEW.available_at <= NOW())
EXECUTE FUNCTION notify_job_queue();
```

Regras importantes:

- NOTIFY não substitui a consulta no banco — é apenas wake-up.
- Worker sempre deve consultar a tabela com claim atômico.
- Em perda de notificação, polling leve periódico (fallback) garante progresso.
- Em conexões via PgBouncer em mode `transaction pooling`, a conexão de LISTEN precisa ser dedicada (fora do pool). Ver seção 12.

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

## 11. Outbox de Eventos para Observabilidade em Tempo Real

A tabela `event_outbox` (V002) implementa o padrão **Transactional Outbox**: quando um job muda de estado, a aplicação grava um evento em `event_outbox` dentro da mesma transação da atualização do job. Isso garante consistência — não existe transição de status sem evento correspondente, e não existe evento sem transição de status.

```sql
CREATE TABLE event_outbox (
  outbox_id BIGSERIAL PRIMARY KEY,
  event_id TEXT NOT NULL UNIQUE,         -- UUID, garante idempotencia na entrega
  aggregate_type TEXT NOT NULL,          -- ex: 'job'
  aggregate_id BIGINT NOT NULL,          -- job_id
  aggregate_version BIGINT NOT NULL,     -- job_version na hora do evento
  occurred_at TIMESTAMPTZ NOT NULL,
  payload JSONB NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_error TEXT,
  sent_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- leitura de eventos pendentes para entrega
CREATE INDEX idx_event_outbox_pending
  ON event_outbox (status, next_attempt_at, outbox_id)
  WHERE status = 'PENDING';

-- cursor para leitura incremental (WebSocket catch-up)
CREATE INDEX idx_event_outbox_cursor
  ON event_outbox (outbox_id, created_at);
```

Um processo dedicado (`OutboxRelay`) lê eventos `PENDING` em loop, entrega via WebSocket a todos os clientes conectados (`QueueEventHub`) e marca o evento como `SENT`. Clientes que reconectam usam o índice de cursor para receber todos os eventos perdidos desde o último `outbox_id` recebido — o frontend nunca perde uma transição de estado mesmo após queda de conexão.

Essa separação tem implicações importantes:

- O caminho crítico do worker (claim → process → ack) não depende de nenhum cliente WebSocket estar conectado.
- A entrega de eventos tem retry próprio, independente do retry de jobs.
- O `event_id` único garante idempotência: reenviar o mesmo evento ao cliente não causa duplicação na UI.

## 12. Escalabilidade

### 12.1 Vertical

Escala bem para volume moderado, desde que:

- índices estejam corretos
- queries de claim sejam curtas
- lotes tenham tamanho controlado

### 12.2 Horizontal

Adicionar workers concorrentes e ajustar:

- `LIMIT` de claim por worker
- número de conexões no pool
- taxa de polling fallback
- `worker_registry` rastreia instâncias; o heartbeat mantém o registro atualizado para reconciliação correta de jobs órfãos entre instâncias.

Quando throughput crescer além do razoável para um único Postgres, a próxima etapa é RabbitMQ como transporte de mensagens, mantendo PostgreSQL como store de estado e histórico. Para streaming massivo com replay ou múltiplos consumidores independentes, Kafka ou Pulsar. Ver [estrategia-de-escala.md](estrategia-de-escala.md) para a análise completa com limites por regime de carga e ordem de implementação por ROI.

## 13. Observabilidade

Metricas minimas:

- `queue_jobs_pending`
- `queue_jobs_processing`
- `queue_jobs_retry`
- `queue_jobs_failed`
- `queue_claim_latency_ms`
- `queue_processing_latency_ms`
- `queue_retries_total`
- `queue_dlq_total`

Consultas operacionais úteis:

```sql
SELECT status, count(*)
FROM job_queue
GROUP BY status;

SELECT queue_name, count(*)
FROM job_queue
WHERE status IN ('PENDING', 'RETRY')
  AND available_at <= NOW()
GROUP BY queue_name;

-- instancias de workers registradas e ultimo heartbeat
SELECT worker_id, status, last_heartbeat_at, processed_count, failed_count
FROM worker_registry
ORDER BY last_heartbeat_at DESC;

-- historico de execucoes de um job especifico
SELECT attempt_number, outcome, error_message, started_at, finished_at
FROM job_execution_history
WHERE job_id = $1
ORDER BY started_at;
```

Além das consultas SQL, a solução expõe um dashboard React atualizado em tempo real via WebSocket. Cada transição de status gera um evento no `event_outbox`; o `OutboxRelay` entrega esse evento ao frontend sem necessidade de polling. O cliente mantém um cursor (`outbox_id`) para recuperar eventos perdidos após reconexão.

Alertas sugeridos:

1. Backlog acima de limiar por mais de N minutos.
2. Crescimento acelerado de `FAILED`.
3. Jobs em `PROCESSING` além do timeout.
4. Workers sem heartbeat por mais de 2× `QUEUE_HEARTBEAT_SECONDS`.

## 14. Segurança

1. Payload sem dados sensíveis desnecessários.
2. Criptografar campos sensíveis quando inevitável.
3. Separar roles SQL de produtor e consumidor.
4. Aplicar TLS nas conexões de aplicação com banco.
5. Auditar acessos administrativos de reprocessamento.
6. `event_outbox.payload` segue as mesmas regras do `job_queue.payload` — não incluir dados sensíveis que não precisam ser transmitidos aos clientes WebSocket.

## 15. Implementação de Referência (esqueleto)

```text
startup worker:
  - registrar worker_id em worker_registry
  - abrir conexao dedicada para LISTEN job_queue_new
  - iniciar loop de claim/process/ack
  - iniciar polling fallback a cada X segundos
  - iniciar heartbeat periodico em worker_registry

loop worker:
  - claim de lote com FOR UPDATE SKIP LOCKED
  - para cada job:
    - processar idempotente
    - gravar entrada em job_execution_history
    - sucesso: DONE + gravar evento em event_outbox (mesma transacao)
    - falha transitoria: RETRY com backoff + gravar evento em event_outbox
    - falha permanente: FAILED (DLQ) + gravar evento em event_outbox

outbox relay (processo separado):
  - ler eventos PENDING de event_outbox ordenados por outbox_id
  - entregar via WebSocket aos clientes conectados (QueueEventHub)
  - marcar como SENT
  - em falha de entrega: incrementar attempts e agendar next_attempt_at

housekeeping:
  - reconciliar PROCESSING expirado (usando locked_at e worker_registry)
  - expurgo de DONE antigos por politica
  - expurgo de event_outbox SENT antigos
  - dashboard e alertas
```

## 16. Trade-offs

Vantagens:

- Menos componentes de infraestrutura
- Transação única entre negócio, fila e publicação de evento (outbox)
- Operação simples para cenários moderados
- Rastreabilidade completa: `job_execution_history` e `event_outbox` formam um audit trail nativo

Custos:

- Banco passa a concentrar mais carga (fila + outbox + histórico)
- Menos recursos nativos de roteamento que brokers dedicados
- Requer disciplina de tuning SQL e housekeeping
- Conexão de LISTEN precisa ser separada do pool se PgBouncer for usado em `transaction pooling`

## 17. Checklist de Produção

1. Tabela de fila com índices e deduplicação criada.
2. Claim com `FOR UPDATE SKIP LOCKED` validado.
3. Retry com backoff e DLQ implementados.
4. Reconciliação de jobs presos ativa (baseada em `locked_at` e `worker_registry`).
5. LISTEN/NOTIFY configurado como wake-up, com polling fallback.
6. Idempotência do consumidor testada.
7. `event_outbox` escrito na mesma transação de cada transição de status.
8. `OutboxRelay` entregando eventos via WebSocket com retry próprio.
9. Dashboards e alertas operacionais publicados.
10. Testes de carga e falha executados (kill de worker, perda de conexão, pico).
11. Benchmark de capacidade executado para determinar teto operacional seguro.

## 18. Conclusão

PostgreSQL pode substituir um provedor de filas com robustez quando a arquitetura adota claim atômico, idempotência, retry controlado, DLQ e observabilidade. LISTEN/NOTIFY funciona como acelerador de wake-up; a garantia real de entrega vem da tabela de fila e do protocolo de consumo.

O padrão Transactional Outbox (`event_outbox`) estende essa garantia para a camada de observabilidade: cada transição de estado é registrada de forma atômica e entregue em tempo real via WebSocket, sem acoplamento entre o caminho crítico do worker e os clientes de monitoramento.

Quando o volume superar o teto prático do PostgreSQL como fila (~5.000 jobs/s), a saída natural é mover o transporte para RabbitMQ (semântica de job queue com DLQ nativa) ou Kafka/Pulsar (streaming com replay). O PostgreSQL permanece como store de estado, histórico e audit trail. Ver [estrategia-de-escala.md](estrategia-de-escala.md) para análise detalhada.
