# QueueLab PG

Projeto demonstrativo para validar PostgreSQL 17 como fila confiavel em cenarios assincronos de volume moderado, com foco em observabilidade operacional e narrativa de demonstracao.

## Estrutura

```text
apps/web-console           Frontend React para monitoramento e simulacao
services/queue-platform    API Javalin + worker Java
database/migrations        Schema SQL do PostgreSQL
infra/docker-compose.yml   Ambiente local com Postgres 17
docs/                      Proposta, arquitetura e planejamento
tasks/                     Plano de execucao e revisao
```

## O que o MVP entrega

- Enfileiramento transacional em `job_queue`
- Wake-up de workers via `LISTEN/NOTIFY`
- Claim concorrente com `FOR UPDATE SKIP LOCKED`
- Retry com backoff exponencial e DLQ logica (`FAILED`)
- Reconciliacao manual de jobs presos em `PROCESSING`
- Dashboard React para backlog, jobs, DLQ e workers
- Simulador React para envio manual, burst e cenarios predefinidos

## Guia de escala

Para orientacoes detalhadas de escalabilidade (workers horizontais, PgBouncer com listener dedicado, particionamento e limites praticos), consulte:

- [docs/estrategia-de-escala.md](docs/estrategia-de-escala.md)

## Compatibilidade com PostgreSQL

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

### Recomendacao de uso

- Recomendado: PostgreSQL 14+
- Ideal: PostgreSQL 17

Essa recomendacao equilibra compatibilidade, estabilidade operacional e ciclo de suporte mais atual.

## Como subir localmente

### Opcao rapida (script unico)

```bash
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
mvn -q -f services/queue-platform/pom.xml exec:java \
	-Dexec.mainClass=com.wedocode.queuelab.api.ApiApplication
```

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

### 1. Banco

```bash
cd infra
docker compose up -d
```

O Postgres sera iniciado com:

- database: `queue_lab`
- user: `postgres`
- password: `admin`

### 2. API Javalin

```bash
cd services/queue-platform
mvn exec:java -Dexec.mainClass=com.wedocode.queuelab.api.ApiApplication
```

Variaveis uteis:

- `QUEUE_API_PORT=7070`
- `QUEUE_DB_URL=jdbc:postgresql://localhost:5432/queue_lab`
- `QUEUE_DB_USER=postgres`
- `QUEUE_DB_PASSWORD=admin`
- `QUEUE_START_EMBEDDED_WORKERS=true`
- `QUEUE_WORKER_THREADS=3`

Se preferir rodar os workers separados:

```bash
cd services/queue-platform
mvn exec:java -Dexec.mainClass=com.wedocode.queuelab.worker.WorkerApplication
```

### 3. Frontend React

```bash
cd apps/web-console
npm install
npm run dev
```

Por padrao, o frontend aponta para `http://localhost:7070`. Para sobrescrever:

```bash
VITE_API_BASE_URL=http://localhost:7070 npm run dev
```

## Fluxo sugerido de demonstracao

1. Inicie o Postgres e a API com workers embutidos.
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

Observacao para execucao por IA:

- Se o ambiente nao tiver `psql`, o script segue em `DB_RESET_MODE=auto` sem `TRUNCATE` e isola os testes por nome de fila por rodada.
- Para forcar a instalacao local do PostgreSQL 17: `PSQL_PATH=/Users/mrcdom/Works/services/pgsql17/bin/psql`.

## Perfil estavel recomendado

Com base nos benchmarks recentes deste equipamento:

- configuracao recomendada: `QUEUE_WORKER_THREADS=10`
- capacidade segura observada: `20 jobs/s`
- alvo operacional recomendado: `16 jobs/s` (margem de seguranca)

Para subir a API no perfil estavel:

```bash
./scripts/start-stable-profile.sh
```

O script aplica migracoes e inicia a API com os parametros recomendados.

## Cenarios predefinidos

- `happy-path`
- `transient-failures`
- `permanent-failures`
- `duplicate-messages`
- `scheduled-jobs`