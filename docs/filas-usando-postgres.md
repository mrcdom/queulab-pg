# Arquitetura de Filas com RabbitMQ e PostgreSQL

## 1. Objetivo

Este documento descreve a arquitetura alvo para processamento assíncrono com:

- RabbitMQ como transporte principal de mensagens
- PostgreSQL como SGBD transacional já adotado pelos sistemas que usarão a solução
- PostgreSQL como store de estado operacional, histórico, deduplicação e observabilidade
- LISTEN/NOTIFY como mecanismo de wake-up para publicação assíncrona e para trilha de auditoria de mudanças do SGBD em uma base de auditoria separada
- WebSocket e outbox para atualização em tempo real do monitor

O objetivo é maximizar a capacidade de escala da solução sem descartar o papel natural do PostgreSQL nos projetos que já o utilizam como banco de dados principal.

## 2. Decisão Arquitetural

Nesta arquitetura, PostgreSQL deixa de ser o mecanismo de entrega da fila e passa a cumprir quatro papéis principais:

1. Persistência transacional de dados de negócio.
2. Registro oficial do estado de cada job.
3. Histórico operacional e trilha de execução.
4. Fonte de auditoria das mudanças relevantes no banco.

RabbitMQ assume o que ele faz melhor:

1. Enfileiramento de alta vazão.
2. Distribuição eficiente para múltiplos consumidores.
3. Ack/nack por mensagem.
4. Retry, DLQ e roteamento por exchange/queue.

## 3. Quando usar esta arquitetura

Use quando:

1. Os sistemas já usam PostgreSQL como banco principal.
2. O throughput esperado é alto o suficiente para que PostgreSQL puro como fila se torne gargalo.
3. É necessário escalar consumidores horizontalmente com menor contention no banco.
4. Há necessidade clara de DLQ, retry e roteamento mais nativos de broker.
5. O histórico e o estado do processamento precisam continuar no banco relacional.

Evite quando:

1. O volume é baixo e a simplicidade operacional é mais importante que throughput.
2. A equipe não quer operar um broker adicional.
3. O caso de uso é puramente síncrono ou quase síncrono.

## 4. Princípios da Arquitetura

1. RabbitMQ é o transporte da mensagem; PostgreSQL é a fonte da verdade do estado.
2. Estado do job e entrega da mensagem não são a mesma coisa e devem ser modelados separadamente.
3. Publicação para RabbitMQ deve sair de um outbox transacional no PostgreSQL.
4. Consumidor deve ser idempotente, porque redelivery continua possível.
5. `LISTEN/NOTIFY` pode ser usado como wake-up pós-commit para o publisher de outbox, mas nunca como transporte da mensagem de negócio.
6. Auditoria do banco não depende do broker; ela usa PostgreSQL + LISTEN/NOTIFY.
7. Eventos de UI continuam desacoplados do caminho crítico por meio de outbox próprio.

## 5. Componentes

### 5.1 PostgreSQL de aplicação

Mantém:

- tabelas de negócio
- registro de jobs
- histórico de execução
- outbox de publicação para RabbitMQ
- outbox de eventos para monitor em tempo real
- estruturas de auditoria do banco

### 5.2 Publisher de outbox para RabbitMQ

Processo dedicado que lê registros pendentes no PostgreSQL, publica no RabbitMQ com publisher confirms e marca a publicação como concluída. Esse processo pode ser acordado por `LISTEN/NOTIFY` após o commit da transação da aplicação, mantendo polling leve como fallback.

### 5.3 RabbitMQ

Responsável por:

- exchange de entrada
- queues de processamento por workload
- DLQ por rota
- filas de retry via TTL e dead-letter exchange

### 5.4 Workers

Consumidores do RabbitMQ que:

1. recebem mensagem
2. processam a carga
3. atualizam o estado do job no PostgreSQL
4. registram histórico
5. fazem `ack`, `nack` ou roteiam para retry/DLQ conforme o resultado

### 5.5 Relay de eventos para UI

Processo que lê `event_outbox`, envia eventos para o monitor via WebSocket e mantém replay curto por cursor.

### 5.6 Relay de auditoria do banco

Processo dedicado que usa `LISTEN/NOTIFY` apenas como wake-up para mudanças auditáveis no PostgreSQL e grava os eventos correspondentes em uma base de auditoria separada.

## 6. Topologia recomendada do RabbitMQ

Topologia lógica mínima:

```text
producer
  -> exchange jobs.direct ou jobs.topic
  -> queue por tipo de trabalho
  -> retry queue com TTL
  -> dead-letter exchange
  -> dead-letter queue
```

Exemplo:

- exchange: `jobs.direct`
- queue principal: `notification.send.q`
- retry queue: `notification.send.retry.q`
- DLQ: `notification.send.dlq`
- routing key: `notification.send`

Estratégia recomendada:

1. Uma queue principal por workload relevante.
2. Uma DLQ por domínio operacional, não necessariamente por consumidor individual.
3. Retry com TTL e dead-lettering, não com sleep no worker.
4. Prefetch controlado para evitar explosão de memória e unfair dispatch.

## 7. Modelo de Dados no PostgreSQL

O PostgreSQL continua central para o estado do processamento, mas não mais para a disputa de consumo da fila.

### 7.1 Registro de jobs

A tabela `job_queue` pode ser preservada, mas com mudança conceitual: ela passa a ser um ledger operacional do job, não a fila física de entrega.

Campos que continuam fazendo sentido:

- `id`
- `queue_name`
- `dedup_key`
- `payload`
- `status`
- `attempts`
- `max_attempts`
- `last_error`
- `job_version`
- `created_at`
- `updated_at`

Campos que deixam de representar claim SQL e passam a ser opcionais ou sem papel central:

- `available_at`
- `locked_at`
- `locked_by`

Campos recomendados para a arquitetura com RabbitMQ:

```sql
ALTER TABLE job_queue
  ADD COLUMN IF NOT EXISTS exchange_name TEXT,
  ADD COLUMN IF NOT EXISTS routing_key TEXT,
  ADD COLUMN IF NOT EXISTS broker_message_id TEXT,
  ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS consumed_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;
```

### 7.2 Outbox de publicação para RabbitMQ

Novo artefato recomendado:

```sql
CREATE TABLE message_outbox (
  outbox_id BIGSERIAL PRIMARY KEY,
  job_id BIGINT NOT NULL REFERENCES job_queue(id) ON DELETE CASCADE,
  exchange_name TEXT NOT NULL,
  routing_key TEXT NOT NULL,
  message_id TEXT NOT NULL UNIQUE,
  payload JSONB NOT NULL,
  headers JSONB,
  status TEXT NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  published_at TIMESTAMPTZ,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_message_outbox_pending
  ON message_outbox (status, next_attempt_at, outbox_id)
  WHERE status = 'PENDING';
```

Essa tabela garante publicação confiável sem depender de uma transação distribuída entre PostgreSQL e RabbitMQ.

### 7.3 Histórico e registro de workers

Continuam úteis:

- `worker_registry`
- `job_execution_history`

Essas tabelas seguem no PostgreSQL porque são dados operacionais e analíticos, não dados de transporte.

### 7.4 Outbox de eventos para UI

`event_outbox` permanece e continua correta para monitoramento em tempo real.

### 7.5 Outbox de auditoria do SGBD

Para auditoria de mudanças do banco com destino a uma base de auditoria separada, recomenda-se:

```sql
CREATE TABLE audit_outbox (
  audit_id BIGSERIAL PRIMARY KEY,
  aggregate_table TEXT NOT NULL,
  aggregate_key TEXT NOT NULL,
  operation_type TEXT NOT NULL,
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  payload JSONB NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  sent_at TIMESTAMPTZ,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_outbox_pending
  ON audit_outbox (status, audit_id)
  WHERE status = 'PENDING';
```

## 8. Produção da Mensagem

Padrão recomendado:

1. Executar alteração de negócio.
2. Inserir ou atualizar o registro correspondente em `job_queue`.
3. Inserir uma linha em `message_outbox` na mesma transação.
4. Commit único no PostgreSQL.
5. Após o commit, a aplicação ou trigger associada executa `pg_notify('queue_publish', queue_name)` como wake-up.
6. Processo assíncrono publica no RabbitMQ lendo o `message_outbox` depois do commit.

Isso evita inconsistências do tipo "negócio confirmado, mensagem perdida" sem exigir XA/2PC, ao mesmo tempo em que reduz a latência entre commit e publicação no broker.

Exemplo:

```sql
BEGIN;

UPDATE appointments
SET status = 'READY_TO_NOTIFY'
WHERE id = 123;

INSERT INTO job_queue (
  queue_name,
  dedup_key,
  payload,
  status,
  exchange_name,
  routing_key,
  job_version
)
VALUES (
  'notification.send',
  'appointment:123:reminder',
  jsonb_build_object('appointmentId', 123, 'channel', 'WHATSAPP'),
  'PENDING',
  'jobs.direct',
  'notification.send',
  1
)
ON CONFLICT DO NOTHING
RETURNING id;

INSERT INTO message_outbox (
  job_id,
  exchange_name,
  routing_key,
  message_id,
  payload
)
VALUES (
  $job_id,
  'jobs.direct',
  'notification.send',
  gen_random_uuid()::text,
  jsonb_build_object('jobId', $job_id)
);

COMMIT;
```

Observação importante: a mensagem publicada no RabbitMQ deve carregar apenas o mínimo necessário. Em geral, publicar `jobId`, `messageId`, `dedupKey` e metadados é melhor do que duplicar o payload inteiro do domínio em todo o pipeline.

Wake-up opcional após commit:

```sql
SELECT pg_notify('queue_publish', 'notification.send');
```

Regras para esse canal:

1. O payload canônico de publicação continua no `message_outbox`.
2. O texto do `NOTIFY` serve apenas para acordar o publisher e, no máximo, indicar a fila ou rota afetada.
3. Se a notificação se perder, o publisher continua recuperando o trabalho pendente por polling leve.

## 9. Consumo com RabbitMQ

O worker deixa de fazer claim em SQL. O fluxo passa a ser:

1. RabbitMQ entrega a mensagem ao worker.
2. Worker abre transação no PostgreSQL.
3. Worker marca o job como `PROCESSING` e incrementa `job_version`.
4. Worker executa a lógica.
5. Worker marca `DONE`, `RETRY` ou `FAILED` no PostgreSQL.
6. Worker registra histórico em `job_execution_history`.
7. Worker confirma no RabbitMQ com `ack` em sucesso ou roteamento controlado de retry/DLQ em falha.

Exemplo de atualização de início do processamento:

```sql
UPDATE job_queue
SET status = 'PROCESSING',
    locked_by = $worker_id,
    locked_at = NOW(),
    broker_message_id = $message_id,
    consumed_at = NOW(),
    job_version = job_version + 1,
    updated_at = NOW()
WHERE id = $job_id;
```

### 9.1 Ack e retry

Política recomendada:

1. Sucesso: atualizar PostgreSQL e depois `ack`.
2. Falha transitória: atualizar PostgreSQL para `RETRY`, publicar em retry queue ou `nack` para fluxo de retry configurado.
3. Falha permanente: atualizar PostgreSQL para `FAILED` e encaminhar para DLQ.

O retry preferencialmente deve ser gerido pelo broker, com TTL e dead-lettering, e não por polling no banco.

### 9.2 Idempotência

Como RabbitMQ pode reenviar mensagens, o consumidor deve ser idempotente por:

- `job_id`
- `dedup_key`
- `message_id`

## 10. Dead Letter Queue

Nesta arquitetura, a DLQ física passa a ser do RabbitMQ.

O PostgreSQL continua registrando o estado lógico do job com `status = 'FAILED'` para:

- consulta operacional
- reprocessamento manual
- dashboard
- relatórios

Isso cria a separação correta:

1. RabbitMQ guarda a mensagem morta para fins de transporte.
2. PostgreSQL guarda o estado morto para fins operacionais e históricos.

## 11. LISTEN/NOTIFY para Wake-up do Publisher e Auditoria do PostgreSQL

`LISTEN/NOTIFY` deixa de ter papel no wake-up de workers, mas continua útil em dois cenários:

1. acordar rapidamente o publisher de `message_outbox` após o commit
2. acordar o relay de auditoria para mudanças do SGBD

### 11.1 Wake-up do publisher de outbox

Fluxo recomendado:

1. A aplicação grava `job_queue` e `message_outbox` na mesma transação.
2. Após o commit, dispara `pg_notify('queue_publish', queue_name)`.
3. Um `OutboxPublisher` mantém uma conexão dedicada com `LISTEN queue_publish`.
4. Ao receber o wake-up, ele consulta `message_outbox` pendente e publica no RabbitMQ.
5. Se nenhuma notificação chegar, polling leve garante progresso eventual.

Exemplo:

```sql
SELECT pg_notify('queue_publish', 'notification.send');
```

Regras importantes:

1. `NOTIFY` não carrega a mensagem de negócio.
2. O payload oficial da publicação vem sempre de `message_outbox`.
3. A conexão de `LISTEN` deve ser dedicada.
4. Em uso de PgBouncer com `transaction pooling`, o listener deve ficar fora desse pool.

### 11.2 Auditoria do banco

Fluxo recomendado:

1. Triggers em tabelas relevantes gravam um registro em `audit_outbox`.
2. A mesma trigger executa `pg_notify('audit_change', '<table-name>')`.
3. Um `AuditRelay` mantém uma conexão dedicada com `LISTEN audit_change`.
4. Ao receber o wake-up, ele busca registros pendentes em `audit_outbox`.
5. O relay persiste os eventos em uma base de auditoria separada.
6. Após sucesso, marca o item auditado como enviado.

Exemplo:

```sql
CREATE OR REPLACE FUNCTION notify_audit_change()
RETURNS trigger AS $$
BEGIN
  INSERT INTO audit_outbox (
    aggregate_table,
    aggregate_key,
    operation_type,
    payload
  )
  VALUES (
    TG_TABLE_NAME,
    COALESCE(NEW.id::text, OLD.id::text),
    TG_OP,
    jsonb_build_object(
      'old', to_jsonb(OLD),
      'new', to_jsonb(NEW)
    )
  );

  PERFORM pg_notify('audit_change', TG_TABLE_NAME);
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;
```

Regras importantes:

1. `NOTIFY` continua sendo apenas wake-up.
2. O payload canônico de auditoria vem do `audit_outbox`, não do texto da notificação.
3. A conexão de `LISTEN` deve ser dedicada.
4. Em uso de PgBouncer com `transaction pooling`, o listener deve ficar fora desse pool.

## 12. Eventos para Observabilidade em Tempo Real

`event_outbox` permanece com o mesmo papel: desacoplar a atualização da UI do caminho crítico de processamento.

Quando o job muda de estado, a aplicação grava um evento em `event_outbox` na mesma transação da mudança do job. Um relay assíncrono entrega esse evento via WebSocket ao monitor.

Essa parte continua válida porque:

1. UI não deve depender diretamente do RabbitMQ.
2. O monitor precisa enxergar o estado oficial do PostgreSQL, não apenas o tráfego do broker.
3. Replay curto por cursor continua simples e determinístico com `event_outbox`.

## 13. Recuperação e Reprocessamento

### 13.1 Falha antes da publicação no RabbitMQ

Se a aplicação caiu após o commit no PostgreSQL e antes da publicação no broker, o `message_outbox` continua `PENDING` e o publisher assíncrono retoma o envio. A perda do `NOTIFY` não compromete a confiabilidade, porque ele é apenas wake-up.

### 13.2 Falha no worker após receber a mensagem

RabbitMQ pode redeliver a mensagem. O worker precisa ser idempotente e o PostgreSQL precisa registrar tentativas e resultado.

### 13.3 Reprocessamento manual

O reprocessamento manual deve:

1. atualizar o estado do job no PostgreSQL
2. gerar nova linha em `message_outbox`
3. republicar para o RabbitMQ

## 14. Escalabilidade

### 14.1 Horizontal

O ganho principal desta arquitetura vem de mover a contenção do banco para o broker apropriado.

Escala horizontal agora ocorre principalmente por:

1. mais consumidores na mesma queue
2. filas separadas por workload
3. exchanges com roteamento por domínio
4. tuning de prefetch e concorrência por worker

### 14.2 Papel do PostgreSQL na escala

O PostgreSQL deixa de participar do caminho de disputa da fila, mas ainda precisa escalar para:

1. escrita de estado do job
2. histórico de execução
3. leitura de dashboard
4. publicação de outbox
5. auditoria

O esforço de escala passa a ser melhor distribuído:

- RabbitMQ absorve burst, roteamento e entrega
- PostgreSQL absorve estado, histórico e consulta

### 14.3 Ordem de implementação por ROI

1. Introduzir `message_outbox` no PostgreSQL.
2. Criar publisher assíncrono com publisher confirms para RabbitMQ e `LISTEN queue_publish` em conexão dedicada.
3. Introduzir exchanges, queues, retry queues e DLQs por workload.
4. Adaptar workers para consumo RabbitMQ com idempotência.
5. Manter `event_outbox` para monitor e `audit_outbox` para auditoria.
6. Separar API, publisher, workers, relay de eventos e relay de auditoria em processos independentes.

## 15. Observabilidade

Métricas mínimas recomendadas:

### 15.1 RabbitMQ

- `rabbitmq_queue_depth`
- `rabbitmq_publish_rate`
- `rabbitmq_ack_rate`
- `rabbitmq_redelivery_rate`
- `rabbitmq_dlq_depth`

### 15.2 PostgreSQL

- `queue_jobs_pending`
- `queue_jobs_processing`
- `queue_jobs_retry`
- `queue_jobs_failed`
- `queue_processing_latency_ms`
- `queue_retries_total`
- `queue_dlq_total`
- `message_outbox_pending`
- `event_outbox_pending`
- `audit_outbox_pending`

Consultas úteis:

```sql
SELECT status, count(*)
FROM job_queue
GROUP BY status;

SELECT queue_name, count(*)
FROM job_queue
WHERE status IN ('PENDING', 'PROCESSING', 'RETRY', 'FAILED')
GROUP BY queue_name;

SELECT worker_id, status, last_heartbeat_at, processed_count, failed_count
FROM worker_registry
ORDER BY last_heartbeat_at DESC;

SELECT attempt_number, outcome, error_message, started_at, finished_at
FROM job_execution_history
WHERE job_id = $1
ORDER BY started_at;
```

Alertas sugeridos:

1. Crescimento de backlog nas queues do RabbitMQ acima do limiar.
2. Crescimento da DLQ do RabbitMQ.
3. Acúmulo anormal em `message_outbox`.
4. `event_outbox` ou `audit_outbox` represados.
5. Jobs em `PROCESSING` por tempo acima do esperado.
6. Redelivery alto no RabbitMQ, indicando falha sistêmica de consumidores.

## 16. Segurança

1. Payload do broker deve carregar apenas o mínimo necessário.
2. Usar TLS nas conexões com RabbitMQ e PostgreSQL.
3. Separar credenciais por papel: API, publisher, worker, relay de eventos e relay de auditoria.
4. Aplicar permissões mínimas em exchanges, queues e vhosts do RabbitMQ.
5. Não usar `NOTIFY` como canal de dados sensíveis; apenas wake-up.
6. `event_outbox` e `audit_outbox` devem seguir política explícita de mascaramento e retenção.

## 17. Resumo da Arquitetura Final

```text
[Aplicacao]
  -> grava negocio + job_queue + message_outbox no PostgreSQL
  -> commit
  -> pg_notify('queue_publish', queue_name)

[Outbox Publisher]
  -> LISTEN queue_publish
  -> le message_outbox
  -> publica no RabbitMQ
  -> marca publicado

[RabbitMQ]
  -> exchange
  -> queue principal
  -> retry queue
  -> DLQ

[Worker]
  -> consome mensagem
  -> processa
  -> atualiza job_queue + job_execution_history + event_outbox
  -> ack/nack

[OutboxRelay]
  -> le event_outbox
  -> envia WebSocket para monitor

[AuditRelay]
  -> LISTEN audit_change no PostgreSQL
  -> le audit_outbox
  -> grava em base de auditoria separada
```

Nesta arquitetura, RabbitMQ maximiza a escala do transporte, PostgreSQL continua como base natural dos projetos que já o utilizam, e `LISTEN/NOTIFY` permanece útil onde ele faz mais sentido: wake-up pós-commit para o publisher e auditoria de mudanças do banco, não transporte da fila de alta vazão.
7. `event_outbox` escrito na mesma transação de cada transição de status.
8. `OutboxRelay` entregando eventos via WebSocket com retry próprio.
9. Dashboards e alertas operacionais publicados.
10. Testes de carga e falha executados (kill de worker, perda de conexão, pico).
11. Benchmark de capacidade executado para determinar teto operacional seguro.

## 18. Conclusão

PostgreSQL pode substituir um provedor de filas com robustez quando a arquitetura adota claim atômico, idempotência, retry controlado, DLQ e observabilidade. LISTEN/NOTIFY funciona como acelerador de wake-up; a garantia real de entrega vem da tabela de fila e do protocolo de consumo.

O padrão Transactional Outbox (`event_outbox`) estende essa garantia para a camada de observabilidade: cada transição de estado é registrada de forma atômica e entregue em tempo real via WebSocket, sem acoplamento entre o caminho crítico do worker e os clientes de monitoramento.

Quando o volume superar o teto prático do PostgreSQL como fila (~5.000 jobs/s), a saída natural é mover o transporte para RabbitMQ (semântica de job queue com DLQ nativa) ou Kafka/Pulsar (streaming com replay). O PostgreSQL permanece como store de estado, histórico e audit trail. Ver [estrategia-de-escala.md](estrategia-de-escala.md) para análise detalhada.
