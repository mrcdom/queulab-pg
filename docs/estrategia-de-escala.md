# Estratégia de escala

Este documento descreve as alavancas disponíveis para escalar a solução além do perfil local de desenvolvimento. As primeiras etapas mantêm o PostgreSQL como backbone — escala horizontal de workers, connection pooling e particionamento. Para volumes acima de ~5.000 jobs/s, há um ponto onde faz sentido introduzir um broker externo (RabbitMQ, Kafka ou Pulsar), com o Postgres assumindo o papel de store de estado e histórico.

---

## Premissa: o que já está pronto

O núcleo da solução já foi projetado para suportar escala horizontal. Os fundamentos são:

- `FOR UPDATE SKIP LOCKED` — múltiplos workers competem pela fila sem double-processing.
- `LISTEN/NOTIFY` por conexão — cada instância recebe wake-up independentemente, sem fanout problemático.
- `worker_registry` + heartbeat — rastreia instâncias vivas e permite reconciliação de jobs órfãos.
- `queue_name` — permite roteamento de carga por fila sem mudança de schema.
- `WorkerApplication` separado — workers podem rodar sem a camada HTTP da API.

---

## 1. Escalar workers horizontalmente (ganho imediato)

Basta subir mais instâncias de `WorkerApplication` (ou `ApiApplication` com `QUEUE_START_EMBEDDED_WORKERS=true`) apontando para o mesmo banco. O `SKIP LOCKED` garante que não haverá duplicação de processamento.

**Pré-requisitos para múltiplas instâncias:**

- Cada instância precisa de um `worker_id` único (gerado por hostname ou UUID na inicialização).
- `QUEUE_HEARTBEAT_SECONDS` deve ser consistente entre instâncias para que a reconciliação de jobs travados funcione corretamente.
- Conexões de `LISTEN` são por processo — cada instância recebe o `NOTIFY` e compete no claim. Isso é o comportamento esperado.

```bash
# Instância 1 — API + workers
QUEUE_WORKER_THREADS=10 QUEUE_API_PORT=7070 \
  mvn exec:java -Dexec.mainClass=com.wedocode.queuelab.api.ApiApplication

# Instância 2 — somente workers (sem porta HTTP)
QUEUE_WORKER_THREADS=10 \
  mvn exec:java -Dexec.mainClass=com.wedocode.queuelab.worker.WorkerApplication
```

---

## 2. Separar API e workers em tiers independentes

Hoje `ApiApplication` pode subir workers embutidos (`QUEUE_START_EMBEDDED_WORKERS=true`). Para escalar os dois planos de forma independente:

```
[API tier]     → stateless, escala horizontal atrás de load balancer
[Worker tier]  → escala conforme throughput necessário, sem porta HTTP exposta
[PostgreSQL]   → escala vertical primeiro, depois read replicas para leitura
```

O `WorkerApplication` separado já existe no projeto — é só ativar esse caminho em vez dos workers embutidos.

---

## 3. Connection pooling com PgBouncer

Com N instâncias × M threads cada, o número de conexões ao Postgres cresce linearmente. PostgreSQL tem custo real por conexão (processo por conexão no modelo tradicional). O limite prático fica entre 100–200 conexões físicas antes de degradar performance.

**Solução:** interpor PgBouncer em modo `transaction pooling`.

```
Worker instances (N×M conexões lógicas)
         ↓
     PgBouncer
         ↓
  PostgreSQL (K conexões físicas — ex: 50–100)
```

**Ressalva crítica:** `LISTEN/NOTIFY` não funciona com `transaction pooling` no PgBouncer — a conexão do listener precisa ser dedicada (via session pooling separado ou conexão direta ao Postgres fora do pool). A implementação atual usa o mesmo pool para LISTEN e processamento — isso precisaria ser separado caso PgBouncer seja introduzido.

---

## 4. Particionamento da fila para reduzir contention

Quando uma única tabela `job_queue` se torna gargalo de lock:

### Opção A — Sharding por `queue_name` (sem mudança de schema)

O campo `queue_name` já existe. Basta dedicar grupos de workers a filas distintas:

```bash
# Grupo de workers para fila de notificações
QUEUE_NAME=notifications QUEUE_WORKER_THREADS=5 ...

# Grupo de workers para fila de relatórios
QUEUE_NAME=reports QUEUE_WORKER_THREADS=3 ...
```

Isso reduz contention porque os workers de cada grupo competem apenas dentro de sua fila.

### Opção B — Particionamento nativo do PostgreSQL

Particionar `job_queue` por `queue_name` usando `PARTITION BY LIST`. Cada partição tem seu próprio espaço de índice e lock, reduzindo contention global.

```sql
CREATE TABLE job_queue (
  ...
) PARTITION BY LIST (queue_name);

CREATE TABLE job_queue_notifications PARTITION OF job_queue
  FOR VALUES IN ('notifications');

CREATE TABLE job_queue_reports PARTITION OF job_queue
  FOR VALUES IN ('reports');
```

### Opção C — Schemas ou databases separados por tenant

Para isolamento total entre domínios ou clientes. Cada tenant tem sua própria tabela `job_queue`, conectado por um pool de conexão dedicado.

---

## 5. Read replicas para observabilidade

Queries do dashboard (`/api/dashboard/snapshot`), listagem de jobs (`/api/jobs`) e DLQ (`/api/dlq`) são leituras pesadas que competem com o caminho crítico dos workers (writes + locks).

Com uma réplica de leitura:

- Workers e enfileiramentos continuam na instância primária.
- Dashboard, listagem e queries de monitoramento vão para a réplica.

No código, `DataSourceFactory` já abstrai a criação do pool — bastaria um segundo `DataSource` apontando para a réplica e roteamento das queries read-only no `QueueRepository`.

---

## 6. Limites práticos do PostgreSQL como fila

| Regime | Abordagem recomendada |
|---|---|
| < 500 jobs/s | PostgreSQL puro, múltiplas instâncias de worker |
| 500–5.000 jobs/s | PgBouncer + particionamento por `queue_name` + workers dedicados por fila |
| > 5.000 jobs/s (semântica de job queue) | **RabbitMQ** como transporte; PostgreSQL mantém estado e histórico |
| > 5.000 jobs/s (streaming / múltiplos consumidores / replay) | **Kafka** ou **Pulsar** como transporte; PostgreSQL como store de estado |

O ponto de inflexão não é o Postgres em si — é o custo do `FOR UPDATE SKIP LOCKED` com alta concorrência em uma única tabela sem particionamento. Com particionamento e múltiplos pools, o teto sobe consideravelmente antes de ser necessário trocar de tecnologia.

### Quando usar RabbitMQ vs Kafka/Pulsar

**RabbitMQ** é semanticamente mais próximo deste projeto. É um broker de mensagens com suporte nativo a DLQ (`x-dead-letter-exchange`), acknowledgment por mensagem individual (`ack`/`nack`) e retry com TTL. A transição seria incremental: RabbitMQ passa a ser o transporte (enfileira o `job_id`), workers consomem e fazem `ack` apenas após processamento, e o PostgreSQL continua rastreando estado, tentativas e histórico por job. Throughput típico: 50k–200k msg/s por nó, com operação mais simples que Kafka.

**Kafka/Pulsar** fazem sentido quando o requisito é outro: streaming massivo (milhões de eventos/s), replay do log histórico, ou múltiplos consumidores independentes que precisam ler o mesmo stream em velocidades diferentes. Para job queue puro, o modelo de offset por partição é menos natural que o `ack` por mensagem do RabbitMQ.

A escolha entre os dois não é de performance bruta — é de modelo semântico: job queue com estado por item → RabbitMQ; stream de eventos com replay → Kafka/Pulsar.

---

## 7. Ordem de implementação por ROI

1. **Separar API e Worker** em processos distintos — já tem o código, é só configuração.
2. **Múltiplas instâncias de Worker** apontando para o mesmo banco.
3. **Sharding por `queue_name`** quando uma fila específica virar gargalo — sem mudança de schema.
4. **PgBouncer** quando o número de conexões físicas ultrapassar ~100.
5. **Particionamento nativo** quando o sharding por nome não for suficiente para reduzir contention.
6. **Read replica** quando o dashboard/observabilidade estiver competindo com o processamento.
7. **RabbitMQ como transporte** quando o PostgreSQL atingir o teto de ~5.000 jobs/s — PostgreSQL assume o papel de store de estado e histórico; RabbitMQ cuida do enfileiramento e entrega. Só avançar para Kafka/Pulsar se o requisito for streaming massivo com replay ou fan-out para múltiplos consumidores independentes.

---

## Referências internas

- [proposta-projeto-demonstrativo.md](proposta-projeto-demonstrativo.md) — contexto e objetivos do projeto
- [filas-usando-postgres.md](filas-usando-postgres.md) — fundamentos técnicos da abordagem com PostgreSQL
- `scripts/run-capacity-benchmark-ai.sh` — benchmark automatizado para medir o teto atual
- `scripts/start-stable-profile.sh` — perfil estável recomendado para este equipamento
