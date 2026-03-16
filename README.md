# QueueLab (RabbitMQ + PostgreSQL)

Projeto demonstrativo para validar uma arquitetura de filas hibrida, com RabbitMQ como transporte principal e PostgreSQL 17 como armazenamento transacional e controle operacional (estado dos jobs, retries, DLQ e outboxes).

## Estrutura

```text
apps/web-console           Frontend React para monitoramento e simulacao
services/queue-platform    API Javalin + worker Java
database/migrations        Schema SQL do PostgreSQL (jobs, event_outbox, message_outbox)
infra/docker-compose.yml   Ambiente local com Postgres 17
docs/                      Proposta, arquitetura e planejamento
tasks/                     Plano de execucao e revisao
```

## O que o MVP entrega

- Enfileiramento transacional em `job_queue`
- Publicacao assincrona via `message_outbox` para RabbitMQ
- Modo de transporte configuravel (`QUEUE_TRANSPORT=postgres|rabbitmq`)
- Worker com `ack/nack`, retry com backoff exponencial e DLQ logica (`FAILED`)
- Reconciliacao manual de jobs presos em `PROCESSING`
- Eventing para observabilidade via `event_outbox`
- Dashboard React para backlog, jobs, DLQ e workers
- Simulador React para envio manual, burst e cenarios predefinidos

## Arquitetura atual (hibrida)

- `rabbitmq` (padrao): API grava no PostgreSQL e publica no RabbitMQ via outbox; workers consomem da fila e consolidam estado no PostgreSQL.
- `postgres`: modo alternativo para execucao sem broker, com workers fazendo claim direto em `job_queue`.

Em ambos os modos, PostgreSQL continua sendo a fonte de verdade para estados e metricas operacionais.

## Compatibilidade com PostgreSQL (storage)

### Matriz de compatibilidade

| Versao | Status | Observacao |
| --- | --- | --- |
| 17 | Validado | Versao alvo do projeto e usada nos testes fim a fim desta implementacao. |
| 12 a 16 | Suportado (alta compatibilidade) | Usa recursos SQL estaveis e maduros nessas versoes. |
| 9.5 a 11 | Compativel tecnicamente | Funciona pelos recursos minimos necessarios, mas nao e faixa recomendada para producao moderna. |

### Minimo tecnico

O minimo tecnico da solucao e PostgreSQL 9.5, por causa de recursos usados diretamente na implementacao:

- `FOR UPDATE SKIP LOCKED`
- `INSERT ... ON CONFLICT`

Para executar em modo `rabbitmq`, tambem e necessario um broker RabbitMQ acessivel pelas variaveis `QUEUE_RABBIT_*`.

### Recomendacao de uso

- Recomendado: PostgreSQL 14+
- Ideal: PostgreSQL 17

Essa recomendacao equilibra compatibilidade, estabilidade operacional e ciclo de suporte mais atual.

## Como subir localmente

### Opcao rapida (script unico)

```bash
# sem RabbitMQ local: force modo postgres
QUEUE_TRANSPORT=postgres ./scripts/run-local-demo.sh

# com RabbitMQ local: usa o modo padrao (rabbitmq)
./scripts/run-local-demo.sh
```

O script executa automaticamente:

1. Cria o banco `queue_lab` se necessario.
2. Aplica as migracoes SQL.
3. Sobe API + workers embutidos.
4. Roda um cenario `happy-path`.
5. Imprime `dashboard`, `jobs` e `workers` para validar o fluxo.

Variaveis suportadas (opcionais):

- `DB_HOST` (padrao `localhost`)
- `DB_PORT` (padrao `5432`)
- `DB_USER` (padrao `postgres`)
- `DB_PASSWORD` (padrao `admin`)
- `DB_ADMIN_NAME` (padrao `postgres`)
- `DB_NAME` (padrao `queue_lab`)
- `API_PORT` (padrao `7070`)
- `QUEUE_TRANSPORT` (`postgres` ou `rabbitmq`; padrao `rabbitmq`)
- `QUEUE_RABBIT_HOST` (padrao `127.0.0.1`)
- `QUEUE_RABBIT_PORT` (padrao `5672`)
- `QUEUE_RABBIT_USER` (padrao `guest`)
- `QUEUE_RABBIT_PASSWORD` (padrao `guest`)
- `QUEUE_RABBIT_VHOST` (padrao `/`)
- `QUEUE_RABBIT_EXCHANGE` (padrao `jobs.direct`)
- `QUEUE_RABBIT_QUEUE` (padrao `notification.send.q`)
- `QUEUE_RABBIT_ROUTING_KEY` (padrao `notification.send`)

### Execucao manual (passo a passo)

Use este fluxo quando quiser controlar cada etapa sem o script automatico.

#### 1. Preparar banco e schema

Opcao A: usar Postgres local ja existente (exemplo com senha `admin`):

```bash
QUEUE_ADMIN_DB_URL='jdbc:postgresql://localhost:5432/postgres' \
QUEUE_DB_USER='postgres' \
QUEUE_DB_PASSWORD='admin' \
QUEUE_TARGET_DB='queue_lab' \
mvn -q -f services/queue-platform/pom.xml exec:java \
	-Dexec.mainClass=com.wedocode.queuelab.core.DatabaseBootstrapApplication

QUEUE_DB_URL='jdbc:postgresql://localhost:5432/queue_lab' \
QUEUE_DB_USER='postgres' \
QUEUE_DB_PASSWORD='admin' \
QUEUE_MIGRATIONS_PATH="$PWD/database/migrations" \
mvn -q -f services/queue-platform/pom.xml exec:java \
	-Dexec.mainClass=com.wedocode.queuelab.core.MigrationApplication
```

Opcao B: subir Postgres via Docker Compose:

```bash
cd infra
docker compose up -d
cd ..

QUEUE_DB_URL='jdbc:postgresql://localhost:5432/queue_lab' \
QUEUE_DB_USER='postgres' \
QUEUE_DB_PASSWORD='admin' \
QUEUE_MIGRATIONS_PATH="$PWD/database/migrations" \
mvn -q -f services/queue-platform/pom.xml exec:java \
	-Dexec.mainClass=com.wedocode.queuelab.core.MigrationApplication
```

#### 2. Subir API com workers embutidos

```bash
QUEUE_DB_URL='jdbc:postgresql://localhost:5432/queue_lab' \
QUEUE_DB_USER='postgres' \
QUEUE_DB_PASSWORD='admin' \
QUEUE_API_PORT='7070' \
QUEUE_START_EMBEDDED_WORKERS='true' \
QUEUE_TRANSPORT='postgres' \
mvn -q -f services/queue-platform/pom.xml exec:java \
	-Dexec.mainClass=com.wedocode.queuelab.api.ApiApplication
```

Para executar em `rabbitmq`, substitua `QUEUE_TRANSPORT='postgres'` por `QUEUE_TRANSPORT='rabbitmq'` e configure `QUEUE_RABBIT_*`.

Se estiver usando Docker Compose sem customizacao, mantenha `QUEUE_DB_PASSWORD='admin'`.

#### 3. Subir frontend

Em outro terminal:

```bash
cd apps/web-console
npm install
npm run dev
```

#### 4. Validar a API manualmente

Em outro terminal:

```bash
curl -sS http://localhost:7070/api/health
curl -sS http://localhost:7070/api/dashboard
curl -sS -X POST http://localhost:7070/api/simulator/scenarios/happy-path
curl -sS 'http://localhost:7070/api/jobs?limit=10'
curl -sS http://localhost:7070/api/workers
```

Se esses comandos retornarem JSON sem erro, a solucao manual esta operacional.

## Fluxo sugerido de demonstracao

1. Inicie o Postgres e a API com workers embutidos (em `postgres` ou `rabbitmq`).
2. Abra o console React e acompanhe o dashboard.
3. Execute um burst ou um cenario predefinido no simulador.
4. Observe jobs passando por `PENDING`, `PROCESSING`, `RETRY`, `DONE` e `FAILED`.
5. Reenfileire jobs da DLQ e use a reconciliacao manual para demonstrar recovery operacional.

## Endpoints principais

- `GET /api/dashboard`
- `GET /api/jobs`
- `GET /api/jobs/{id}`
- `GET /api/dlq`
- `GET /api/workers`
- `POST /api/jobs`
- `POST /api/jobs/{id}/requeue`
- `POST /api/admin/reconcile`
- `POST /api/simulator/burst`
- `POST /api/simulator/scenarios/{scenario}`

## Benchmark de capacidade (AI-first)

Para medir capacidade de processamento com criterios objetivos (throughput sustentavel, crescimento de backlog, eficiencia e taxa de falha), execute:

```bash
./scripts/run-capacity-benchmark-ai.sh
```

O script:

1. Aplica migracoes.
2. Roda uma matriz de carga por `workers x rate`.
3. Coleta amostras operacionais em CSV.
4. Calcula `PASS/FAIL` por ponto.
5. Gera o resultado de capacidade segura.

Arquivos de saida:

- `.tmp/capacity-benchmark-<timestamp>/summary.csv`
- `.tmp/capacity-benchmark-<timestamp>/samples.csv`
- `.tmp/capacity-benchmark-<timestamp>/capacity-result.txt`
- `.tmp/capacity-benchmark-<timestamp>/capacity-report.md`

Variaveis configuraveis:

- `WORKER_THREADS_LIST` (padrao: `1 2 4 8`)
- `TARGET_RATES` (padrao: `25 50 100 150 200`)
- `WARMUP_SECONDS` (padrao: `60`)
- `MEASURE_SECONDS` (padrao: `180`)
- `SAMPLE_INTERVAL_SECONDS` (padrao: `5`)
- `MAX_BACKLOG_GROWTH` (padrao: `5`)
- `MAX_FAILED_RATE` (padrao: `0.005`)
- `MIN_EFFICIENCY` (padrao: `0.90`)
- `DB_RESET_MODE` (`auto`, `required`, `off`; padrao: `auto`)
- `PSQL_PATH` (caminho absoluto do `psql`, opcional)
- `QUEUE_TRANSPORT` (`postgres` ou `rabbitmq`; padrao: `rabbitmq`)
- `BENCHMARK_QUEUE_NAME` (opcional; quando definido, fixa a fila/rota usada em todos os pontos de teste)
- `QUEUE_RABBIT_HOST` (padrao: `127.0.0.1`)
- `QUEUE_RABBIT_PORT` (padrao: `5672`)
- `QUEUE_RABBIT_EXCHANGE` (padrao: `jobs.direct`)
- `QUEUE_RABBIT_QUEUE` (padrao: `notification.send.q`)
- `QUEUE_RABBIT_ROUTING_KEY` (padrao: `notification.send`)

Observacao para execucao por IA:

- Se o ambiente nao tiver `psql`, o script segue em `DB_RESET_MODE=auto` sem `TRUNCATE` e isola os testes por nome de fila por rodada.
- Para forcar a instalacao local do PostgreSQL 17: `PSQL_PATH=/Users/mrcdom/Works/services/pgsql17/bin/psql`.

Observacao para RabbitMQ:

- Se os workers estiverem vinculados a uma routing key/fila fixa (ex.: `notification.send`), defina `BENCHMARK_QUEUE_NAME=notification.send` para evitar backlog artificial por roteamento sem consumidor.

## Resultados de benchmark (mar/2026)

Foram executadas matrizes comparativas com os mesmos parametros em `QUEUE_TRANSPORT=postgres` e `QUEUE_TRANSPORT=rabbitmq`.

Resumo consolidado:

| Matriz | PostgreSQL-only (safe capacity) | RabbitMQ (safe capacity) | Leitura pratica |
| --- | --- | --- | --- |
| `workers=2 4 8`, `rates=5 10 15` | `workers=8, rate=15, throughput=15.85 jobs/s` | `workers=8, rate=15, throughput=15.65 jobs/s` | Diferenca pequena; empate tecnico. |
| `workers=8 12 16`, `rates=20 30 40` | `workers=16, rate=30, throughput=30.97 jobs/s` | `workers=16, rate=30, throughput=30.97 jobs/s` | Empate tecnico. |
| `workers=16 24 32`, `rates=50 70 90` | `workers=24, rate=50, throughput=50.26 jobs/s` | `workers=24, rate=50, throughput=50.11 jobs/s` | Empate tecnico (RabbitMQ ~0.28% abaixo neste ponto). |

Conclusao dos testes atuais:

- Nao houve ganho relevante de throughput bruto com RabbitMQ neste hardware/perfil de carga.
- O beneficio do RabbitMQ aparece mais em aspectos operacionais (desacoplamento, roteamento, elasticidade e governanca), nao em capacidade maxima observada neste experimento.


## Perfil estavel recomendado

Com base nos benchmarks recentes deste equipamento:

- configuracao estavel validada: `QUEUE_WORKER_THREADS=24`
- capacidade segura observada: `50 jobs/s`
- alvo operacional recomendado: `40 jobs/s` (margem de seguranca de 20%)

Comando de referencia (matriz agressiva):

```bash
WORKER_THREADS_LIST='16 24 32' TARGET_RATES='50 70 90' WARMUP_SECONDS='10' MEASURE_SECONDS='35' SAMPLE_INTERVAL_SECONDS='5' DB_RESET_MODE='required' PSQL_PATH='/Users/mrcdom/Works/services/pgsql17/bin/psql' API_PORT='7100' QUEUE_TRANSPORT='postgres' ./scripts/run-capacity-benchmark-ai.sh

WORKER_THREADS_LIST='16 24 32' TARGET_RATES='50 70 90' WARMUP_SECONDS='10' MEASURE_SECONDS='35' SAMPLE_INTERVAL_SECONDS='5' DB_RESET_MODE='required' PSQL_PATH='/Users/mrcdom/Works/services/pgsql17/bin/psql' API_PORT='7101' QUEUE_TRANSPORT='rabbitmq' BENCHMARK_QUEUE_NAME='notification.send' QUEUE_RABBIT_QUEUE='notification.send.q' QUEUE_RABBIT_ROUTING_KEY='notification.send' QUEUE_RABBIT_EXCHANGE='jobs.direct' ./scripts/run-capacity-benchmark-ai.sh
```

O script aplica migracoes e inicia a API com os parametros recomendados.

## Cenarios predefinidos

- `happy-path`
- `transient-failures`
- `permanent-failures`
- `duplicate-messages`
- `scheduled-jobs`