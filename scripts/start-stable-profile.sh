#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM_PATH="$ROOT_DIR/services/queue-platform/pom.xml"
MIGRATIONS_PATH="$ROOT_DIR/database/migrations"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-admin}"
DB_NAME="${DB_NAME:-queue_lab}"
API_PORT="${API_PORT:-7070}"

# Perfil estável recomendado para este equipamento após benchmark:
# - 10 workers
# - claim batch 10
# - fallback poll 4000ms
# - idle sleep 750ms
# - objetivo de tráfego de entrada: ~16 jobs/s (pico curto até 20 jobs/s)
QUEUE_WORKER_THREADS="${QUEUE_WORKER_THREADS:-10}"
QUEUE_CLAIM_BATCH_SIZE="${QUEUE_CLAIM_BATCH_SIZE:-10}"
QUEUE_FALLBACK_POLL_MS="${QUEUE_FALLBACK_POLL_MS:-4000}"
QUEUE_IDLE_SLEEP_MS="${QUEUE_IDLE_SLEEP_MS:-750}"
QUEUE_PROCESSING_TIMEOUT_SECONDS="${QUEUE_PROCESSING_TIMEOUT_SECONDS:-90}"
QUEUE_HEARTBEAT_SECONDS="${QUEUE_HEARTBEAT_SECONDS:-10}"

echo "[1/2] Aplicando migrações..."
QUEUE_DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
QUEUE_DB_USER="$DB_USER" \
QUEUE_DB_PASSWORD="$DB_PASSWORD" \
QUEUE_MIGRATIONS_PATH="$MIGRATIONS_PATH" \
mvn -q -f "$POM_PATH" exec:java -Dexec.mainClass=com.wedocode.queuelab.core.MigrationApplication

echo "[2/2] Iniciando API em perfil estável..."
QUEUE_DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
QUEUE_DB_USER="$DB_USER" \
QUEUE_DB_PASSWORD="$DB_PASSWORD" \
QUEUE_API_PORT="$API_PORT" \
QUEUE_START_EMBEDDED_WORKERS="true" \
QUEUE_WORKER_THREADS="$QUEUE_WORKER_THREADS" \
QUEUE_CLAIM_BATCH_SIZE="$QUEUE_CLAIM_BATCH_SIZE" \
QUEUE_FALLBACK_POLL_MS="$QUEUE_FALLBACK_POLL_MS" \
QUEUE_IDLE_SLEEP_MS="$QUEUE_IDLE_SLEEP_MS" \
QUEUE_PROCESSING_TIMEOUT_SECONDS="$QUEUE_PROCESSING_TIMEOUT_SECONDS" \
QUEUE_HEARTBEAT_SECONDS="$QUEUE_HEARTBEAT_SECONDS" \
mvn -q -f "$POM_PATH" exec:java -Dexec.mainClass=com.wedocode.queuelab.api.ApiApplication
