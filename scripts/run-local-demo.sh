#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM_PATH="$ROOT_DIR/services/queue-platform/pom.xml"
MIGRATIONS_PATH="$ROOT_DIR/database/migrations"
LOG_DIR="$ROOT_DIR/.tmp"
LOG_FILE="$LOG_DIR/queue-api.log"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-admin}"
DB_ADMIN_NAME="${DB_ADMIN_NAME:-postgres}"
DB_NAME="${DB_NAME:-queue_lab}"
API_PORT="${API_PORT:-7070}"

mkdir -p "$LOG_DIR"

echo "[1/5] Criando banco '$DB_NAME' (se necessario)..."
QUEUE_ADMIN_DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_ADMIN_NAME" \
QUEUE_DB_USER="$DB_USER" \
QUEUE_DB_PASSWORD="$DB_PASSWORD" \
QUEUE_TARGET_DB="$DB_NAME" \
mvn -q -f "$POM_PATH" exec:java -Dexec.mainClass=com.wedocode.queuelab.core.DatabaseBootstrapApplication

echo "[2/5] Aplicando migracoes em '$DB_NAME'..."
QUEUE_DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
QUEUE_DB_USER="$DB_USER" \
QUEUE_DB_PASSWORD="$DB_PASSWORD" \
QUEUE_MIGRATIONS_PATH="$MIGRATIONS_PATH" \
mvn -q -f "$POM_PATH" exec:java -Dexec.mainClass=com.wedocode.queuelab.core.MigrationApplication

echo "[3/5] Iniciando API + workers embutidos (porta $API_PORT)..."
QUEUE_DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
QUEUE_DB_USER="$DB_USER" \
QUEUE_DB_PASSWORD="$DB_PASSWORD" \
QUEUE_API_PORT="$API_PORT" \
QUEUE_START_EMBEDDED_WORKERS="true" \
mvn -q -f "$POM_PATH" exec:java -Dexec.mainClass=com.wedocode.queuelab.api.ApiApplication >"$LOG_FILE" 2>&1 &

API_PID=$!

cleanup() {
  if kill -0 "$API_PID" >/dev/null 2>&1; then
    kill "$API_PID" >/dev/null 2>&1 || true
    wait "$API_PID" 2>/dev/null || true
  fi
}

trap cleanup EXIT

echo "[4/5] Aguardando health check da API..."
for _ in $(seq 1 45); do
  if curl -fsS "http://localhost:$API_PORT/api/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

if ! curl -fsS "http://localhost:$API_PORT/api/health" >/dev/null 2>&1; then
  echo "Falha ao subir a API. Veja o log em: $LOG_FILE"
  exit 1
fi

echo "[5/5] Validando fluxo demonstrativo..."
DASHBOARD_JSON="$(curl -fsS "http://localhost:$API_PORT/api/dashboard")"
SCENARIO_JSON="$(curl -fsS -X POST "http://localhost:$API_PORT/api/simulator/scenarios/happy-path")"
sleep 3
JOBS_JSON="$(curl -fsS "http://localhost:$API_PORT/api/jobs?limit=10")"
WORKERS_JSON="$(curl -fsS "http://localhost:$API_PORT/api/workers")"

echo
echo "Dashboard:"
echo "$DASHBOARD_JSON"
echo
echo "Scenario happy-path:"
echo "$SCENARIO_JSON"
echo
echo "Jobs (limit=10):"
echo "$JOBS_JSON"
echo
echo "Workers:"
echo "$WORKERS_JSON"
echo
echo "Demo validada com sucesso."
echo "Log da API: $LOG_FILE"