#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM_PATH="$ROOT_DIR/services/queue-platform/pom.xml"
MIGRATIONS_PATH="$ROOT_DIR/database/migrations"
TMP_DIR="$ROOT_DIR/.tmp"

PGB_DIR="${PGB_DIR:-/Users/mrcdom/Works/services/pgbouncer}"
PSQL_PATH="${PSQL_PATH:-/Users/mrcdom/Works/services/pgsql17/bin/psql}"

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-admin}"
DB_NAME="${DB_NAME:-queue_lab}"

PGB_HOST="${PGB_HOST:-127.0.0.1}"
PGB_PORT="${PGB_PORT:-6432}"

API_PORT="${API_PORT:-7070}"
WORKER_THREADS="${WORKER_THREADS:-3}"
START_PGBOUNCER="${START_PGBOUNCER:-true}"

PGB_PID=""
API_PID=""

mkdir -p "$TMP_DIR"
LOG_FILE="$TMP_DIR/pgbouncer-split-smoke-$(date +%Y%m%d-%H%M%S).log"

log() {
  local msg="$1"
  echo "[$(date +%H:%M:%S)] $msg" | tee -a "$LOG_FILE"
}

cleanup() {
  if [[ -n "$API_PID" ]] && kill -0 "$API_PID" >/dev/null 2>&1; then
    log "Encerrando API (pid=$API_PID)"
    kill "$API_PID" >/dev/null 2>&1 || true
    wait "$API_PID" 2>/dev/null || true
  fi

  if [[ "$START_PGBOUNCER" == "true" ]]; then
    if [[ -x "$PGB_DIR/stop.sh" ]]; then
      log "Encerrando PgBouncer via stop.sh"
      "$PGB_DIR/stop.sh" >/dev/null 2>&1 || true
    elif [[ -n "$PGB_PID" ]] && kill -0 "$PGB_PID" >/dev/null 2>&1; then
      log "Encerrando PgBouncer (pid=$PGB_PID)"
      kill "$PGB_PID" >/dev/null 2>&1 || true
      wait "$PGB_PID" 2>/dev/null || true
    fi
  fi
}

trap cleanup EXIT

wait_http_ok() {
  local url="$1"
  local attempts="${2:-45}"
  local i=1
  while [[ $i -le $attempts ]]; do
    if curl -fsS "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  return 1
}

wait_port_listen() {
  local port="$1"
  local attempts="${2:-20}"
  local i=1
  while [[ $i -le $attempts ]]; do
    if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    i=$((i + 1))
  done
  return 1
}

require_file() {
  local path="$1"
  if [[ ! -f "$path" ]]; then
    echo "Arquivo nao encontrado: $path"
    exit 1
  fi
}

require_dir() {
  local path="$1"
  if [[ ! -d "$path" ]]; then
    echo "Diretorio nao encontrado: $path"
    exit 1
  fi
}

require_exec() {
  local path="$1"
  if [[ ! -x "$path" ]]; then
    echo "Executavel nao encontrado: $path"
    exit 1
  fi
}

require_file "$POM_PATH"
require_dir "$MIGRATIONS_PATH"
require_exec "$PSQL_PATH"

if [[ "$START_PGBOUNCER" == "true" ]]; then
  require_exec "$PGB_DIR/run.sh"
  log "Subindo PgBouncer"
  "$PGB_DIR/stop.sh" >/dev/null 2>&1 || true
  "$PGB_DIR/run.sh" >>"$LOG_FILE" 2>&1 &
  PGB_PID="$!"

  if ! wait_port_listen "$PGB_PORT" 20; then
    log "PgBouncer nao subiu na porta $PGB_PORT"
    exit 1
  fi

  log "Validando conexao via PgBouncer"
  PGPASSWORD="$DB_PASSWORD" "$PSQL_PATH" \
    -h "$PGB_HOST" -p "$PGB_PORT" -U "$DB_USER" -d "$DB_NAME" \
    -c 'select 1 as pgbouncer_ok;' >>"$LOG_FILE"
fi

log "Aplicando migracoes"
QUEUE_DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
QUEUE_DB_USER="$DB_USER" \
QUEUE_DB_PASSWORD="$DB_PASSWORD" \
QUEUE_MIGRATIONS_PATH="$MIGRATIONS_PATH" \
mvn -q -f "$POM_PATH" exec:java -Dexec.mainClass=com.wedocode.queuelab.core.MigrationApplication >>"$LOG_FILE" 2>&1

log "Subindo API com split de conexao (DB via PgBouncer + listener direto)"
QUEUE_DB_URL="jdbc:postgresql://$PGB_HOST:$PGB_PORT/$DB_NAME" \
QUEUE_DB_USER="$DB_USER" \
QUEUE_DB_PASSWORD="$DB_PASSWORD" \
QUEUE_DB_LISTENER_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
QUEUE_DB_LISTENER_USER="$DB_USER" \
QUEUE_DB_LISTENER_PASSWORD="$DB_PASSWORD" \
QUEUE_API_PORT="$API_PORT" \
QUEUE_START_EMBEDDED_WORKERS="true" \
QUEUE_WORKER_THREADS="$WORKER_THREADS" \
mvn -q -f "$POM_PATH" exec:java -Dexec.mainClass=com.wedocode.queuelab.api.ApiApplication >>"$LOG_FILE" 2>&1 &
API_PID="$!"

if ! wait_http_ok "http://localhost:$API_PORT/api/health" 50; then
  log "API nao ficou saudavel na porta $API_PORT"
  exit 1
fi

RECIPIENT="pgbouncer-split-$(date +%s)@test.local"
log "Enfileirando job de teste ($RECIPIENT)"
CREATE_JSON="$(curl -fsS -X POST "http://localhost:$API_PORT/api/jobs" \
  -H 'Content-Type: application/json' \
  -d "{\"queueName\":\"notification.send\",\"payload\":{\"channel\":\"EMAIL\",\"recipient\":\"$RECIPIENT\",\"simulatedDurationMs\":120},\"maxAttempts\":6}")"

JOB_ID="$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["jobId"])' <<<"$CREATE_JSON")"
log "Job criado: id=$JOB_ID"

log "Aguardando job chegar em DONE"
DONE="false"
for _ in $(seq 1 30); do
  JOB_JSON="$(curl -fsS "http://localhost:$API_PORT/api/jobs/$JOB_ID")"
  STATUS="$(python3 -c 'import json,sys; print(json.loads(sys.stdin.read())["status"])' <<<"$JOB_JSON")"
  if [[ "$STATUS" == "DONE" ]]; then
    DONE="true"
    break
  fi
  sleep 1
done

if [[ "$DONE" != "true" ]]; then
  log "Job $JOB_ID nao chegou em DONE"
  exit 1
fi

log "Validando eventos de outbox para aggregate_id=$JOB_ID"
OUTBOX_ROWS="$(PGPASSWORD="$DB_PASSWORD" "$PSQL_PATH" -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -t -A -F',' -c "select count(*) from event_outbox where aggregate_id = $JOB_ID and status = 'SENT';")"
OUTBOX_ROWS="$(echo "$OUTBOX_ROWS" | tr -d '[:space:]')"

if [[ -z "$OUTBOX_ROWS" || "$OUTBOX_ROWS" -lt 1 ]]; then
  log "Nenhum evento SENT encontrado na outbox para job $JOB_ID"
  exit 1
fi

log "Smoke test concluido com sucesso"
log "Resumo: job_id=$JOB_ID, outbox_sent=$OUTBOX_ROWS"
log "Log detalhado: $LOG_FILE"
