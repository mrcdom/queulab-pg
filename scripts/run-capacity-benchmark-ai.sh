#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
POM_PATH="$ROOT_DIR/services/queue-platform/pom.xml"
MIGRATIONS_PATH="$ROOT_DIR/database/migrations"
RESULTS_ROOT="$ROOT_DIR/.tmp"
TIMESTAMP="$(date +%Y%m%d-%H%M%S)"
RESULTS_DIR="$RESULTS_ROOT/capacity-benchmark-$TIMESTAMP"
SUMMARY_CSV="$RESULTS_DIR/summary.csv"
SAMPLES_CSV="$RESULTS_DIR/samples.csv"
REPORT_MD="$RESULTS_DIR/capacity-report.md"
RUN_LOG="$RESULTS_DIR/run.log"
API_LOG_DIR="$RESULTS_DIR/api-logs"

DB_HOST="${DB_HOST:-localhost}"
DB_PORT="${DB_PORT:-5432}"
DB_USER="${DB_USER:-postgres}"
DB_PASSWORD="${DB_PASSWORD:-admin}"
DB_NAME="${DB_NAME:-queue_lab}"
API_PORT="${API_PORT:-7070}"

WORKER_THREADS_LIST="${WORKER_THREADS_LIST:-1 2 4 8}"
TARGET_RATES="${TARGET_RATES:-25 50 100 150 200}"

WARMUP_SECONDS="${WARMUP_SECONDS:-60}"
MEASURE_SECONDS="${MEASURE_SECONDS:-180}"
SAMPLE_INTERVAL_SECONDS="${SAMPLE_INTERVAL_SECONDS:-5}"

MAX_BACKLOG_GROWTH="${MAX_BACKLOG_GROWTH:-5}"
MAX_FAILED_RATE="${MAX_FAILED_RATE:-0.005}"
MIN_EFFICIENCY="${MIN_EFFICIENCY:-0.90}"
DB_RESET_MODE="${DB_RESET_MODE:-auto}"
PSQL_PATH="${PSQL_PATH:-}"

mkdir -p "$RESULTS_DIR" "$API_LOG_DIR"

log() {
  local msg="$1"
  echo "[$(date +%H:%M:%S)] $msg" | tee -a "$RUN_LOG"
}

require_command() {
  local command="$1"
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "Comando obrigatorio nao encontrado: $command"
    exit 1
  fi
}

require_command curl
require_command mvn
require_command python3
if [[ -z "$PSQL_PATH" ]]; then
  if command -v psql >/dev/null 2>&1; then
    PSQL_PATH="$(command -v psql)"
  elif [[ -x "/Users/mrcdom/Works/services/pgsql17/bin/psql" ]]; then
    PSQL_PATH="/Users/mrcdom/Works/services/pgsql17/bin/psql"
  fi
fi

PSQL_AVAILABLE="false"
if [[ -n "$PSQL_PATH" && -x "$PSQL_PATH" ]]; then
  PSQL_AVAILABLE="true"
fi

api_pid=""

cleanup() {
  if [[ -n "$api_pid" ]] && kill -0 "$api_pid" >/dev/null 2>&1; then
    kill "$api_pid" >/dev/null 2>&1 || true
    wait "$api_pid" 2>/dev/null || true
  fi
}

trap cleanup EXIT

wait_health() {
  local max_attempts=60
  local attempt=1
  while [[ $attempt -le $max_attempts ]]; do
    if curl -fsS "http://localhost:$API_PORT/api/health" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
    attempt=$((attempt + 1))
  done
  return 1
}

apply_migrations() {
  log "Aplicando migracoes..."
  QUEUE_DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
  QUEUE_DB_USER="$DB_USER" \
  QUEUE_DB_PASSWORD="$DB_PASSWORD" \
  QUEUE_MIGRATIONS_PATH="$MIGRATIONS_PATH" \
  mvn -q -f "$POM_PATH" exec:java -Dexec.mainClass=com.wedocode.queuelab.core.MigrationApplication >>"$RUN_LOG" 2>&1
}

reset_tables() {
  if [[ "$DB_RESET_MODE" == "off" ]]; then
    log "Reset de banco desativado por DB_RESET_MODE=off"
    return
  fi

  if [[ "$PSQL_AVAILABLE" != "true" ]]; then
    if [[ "$DB_RESET_MODE" == "required" ]]; then
      echo "DB_RESET_MODE=required mas psql nao esta disponivel."
      exit 1
    fi
    log "psql nao encontrado; seguindo sem TRUNCATE (modo auto)."
    return
  fi

  log "Limpando estado das tabelas de fila..."
  PGPASSWORD="$DB_PASSWORD" "$PSQL_PATH" -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -v ON_ERROR_STOP=1 -q <<'SQL' >>"$RUN_LOG" 2>&1
TRUNCATE TABLE job_execution_history RESTART IDENTITY CASCADE;
TRUNCATE TABLE worker_registry RESTART IDENTITY CASCADE;
TRUNCATE TABLE event_outbox RESTART IDENTITY CASCADE;
TRUNCATE TABLE job_queue RESTART IDENTITY CASCADE;
SQL
}

start_api() {
  local workers="$1"
  local api_log="$API_LOG_DIR/api-w${workers}.log"

  log "Iniciando API (workers=$workers)..."
  QUEUE_DB_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME" \
  QUEUE_DB_USER="$DB_USER" \
  QUEUE_DB_PASSWORD="$DB_PASSWORD" \
  QUEUE_API_PORT="$API_PORT" \
  QUEUE_START_EMBEDDED_WORKERS="true" \
  QUEUE_WORKER_THREADS="$workers" \
  mvn -q -f "$POM_PATH" exec:java -Dexec.mainClass=com.wedocode.queuelab.api.ApiApplication >"$api_log" 2>&1 &

  api_pid=$!

  if ! wait_health; then
    log "Falha ao subir API. Verifique: $api_log"
    exit 1
  fi
}

stop_api() {
  if [[ -n "$api_pid" ]] && kill -0 "$api_pid" >/dev/null 2>&1; then
    kill "$api_pid" >/dev/null 2>&1 || true
    wait "$api_pid" 2>/dev/null || true
  fi
  api_pid=""
}

dashboard_snapshot() {
  curl -fsS "http://localhost:$API_PORT/api/dashboard/snapshot"
}

send_burst() {
  local rate="$1"
  local queue_name="$2"

  curl -fsS -X POST "http://localhost:$API_PORT/api/simulator/burst" \
    -H 'Content-Type: application/json' \
    -d "{\"queueName\":\"$queue_name\",\"count\":$rate,\"transientFailuresBeforeSuccess\":0,\"permanentFailures\":0,\"useDeduplication\":false,\"repeatDedupKey\":false,\"dedupPrefix\":\"benchmark\",\"maxAttempts\":6}" >/dev/null
}

extract_metrics() {
  local json="$1"
  python3 -c '
import json, sys
obj = json.loads(sys.stdin.read())
status = obj.get("statusCounts", {})
print(status.get("PENDING", 0), status.get("PROCESSING", 0), status.get("RETRY", 0), status.get("DONE", 0), status.get("FAILED", 0), obj.get("activeWorkers", 0), obj.get("averageWaitSeconds", 0.0), obj.get("averageProcessingSeconds", 0.0))
' <<<"$json"
}

append_sample() {
  local workers="$1"
  local rate="$2"
  local phase="$3"
  local elapsed="$4"
  local json="$5"
  local parsed
  parsed="$(extract_metrics "$json")"
  local pending processing retry done failed active avg_wait avg_proc
  read -r pending processing retry done failed active avg_wait avg_proc <<<"$parsed"

  echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),$workers,$rate,$phase,$elapsed,$pending,$processing,$retry,$done,$failed,$active,$avg_wait,$avg_proc" >>"$SAMPLES_CSV"
}

evaluate_run() {
  local workers="$1"
  local rate="$2"
  local start_json="$3"
  local end_json="$4"

  local start_parsed end_parsed
  start_parsed="$(extract_metrics "$start_json")"
  end_parsed="$(extract_metrics "$end_json")"

  local s_pending s_processing s_retry s_done s_failed s_active s_wait s_proc
  local e_pending e_processing e_retry e_done e_failed e_active e_wait e_proc

  read -r s_pending s_processing s_retry s_done s_failed s_active s_wait s_proc <<<"$start_parsed"
  read -r e_pending e_processing e_retry e_done e_failed e_active e_wait e_proc <<<"$end_parsed"

  local start_backlog=$((s_pending + s_processing + s_retry))
  local end_backlog=$((e_pending + e_processing + e_retry))
  local backlog_growth=$((end_backlog - start_backlog))
  local done_delta=$((e_done - s_done))
  local failed_delta=$((e_failed - s_failed))

  local metrics
  metrics="$(python3 - "$done_delta" "$failed_delta" "$rate" "$MEASURE_SECONDS" <<'PY'
import sys

done_delta = float(sys.argv[1])
failed_delta = float(sys.argv[2])
rate = float(sys.argv[3])
measure_seconds = float(sys.argv[4])

throughput = done_delta / measure_seconds if measure_seconds > 0 else 0.0
total_terminal = done_delta + failed_delta
failed_rate = (failed_delta / total_terminal) if total_terminal > 0 else 0.0
efficiency = (throughput / rate) if rate > 0 else 0.0

print(f"{throughput:.4f} {failed_rate:.6f} {efficiency:.4f}")
PY
)"

  local throughput failed_rate efficiency
  read -r throughput failed_rate efficiency <<<"$metrics"

  local passed
  passed="$(python3 - "$backlog_growth" "$MAX_BACKLOG_GROWTH" "$failed_rate" "$MAX_FAILED_RATE" "$efficiency" "$MIN_EFFICIENCY" <<'PY'
import sys

backlog_growth = float(sys.argv[1])
max_backlog_growth = float(sys.argv[2])
failed_rate = float(sys.argv[3])
max_failed_rate = float(sys.argv[4])
efficiency = float(sys.argv[5])
min_efficiency = float(sys.argv[6])

ok = backlog_growth <= max_backlog_growth and failed_rate <= max_failed_rate and efficiency >= min_efficiency
print("PASS" if ok else "FAIL")
PY
)"

  echo "$workers,$rate,$throughput,$efficiency,$backlog_growth,$failed_rate,$s_wait,$e_wait,$s_proc,$e_proc,$passed" >>"$SUMMARY_CSV"

  log "Resultado workers=$workers rate=$rate => $passed (throughput=$throughput jobs/s, efficiency=$efficiency, backlog_growth=$backlog_growth, failed_rate=$failed_rate)"
}

run_point() {
  local workers="$1"
  local rate="$2"
  local queue_name="benchmark.w${workers}.r${rate}.$TIMESTAMP"

  reset_tables
  start_api "$workers"

  log "Executando ponto de carga workers=$workers rate=$rate jobs/s"

  local total_seconds=$((WARMUP_SECONDS + MEASURE_SECONDS))
  local second=1

  local measure_start_json=""
  while [[ $second -le $total_seconds ]]; do
    send_burst "$rate" "$queue_name"

    if (( second % SAMPLE_INTERVAL_SECONDS == 0 )); then
      local phase="warmup"
      if (( second > WARMUP_SECONDS )); then
        phase="measure"
      fi
      local snap
      snap="$(dashboard_snapshot)"
      append_sample "$workers" "$rate" "$phase" "$second" "$snap"
    fi

    if (( second == WARMUP_SECONDS )); then
      measure_start_json="$(dashboard_snapshot)"
    fi

    sleep 1
    second=$((second + 1))
  done

  local measure_end_json
  measure_end_json="$(dashboard_snapshot)"

  evaluate_run "$workers" "$rate" "$measure_start_json" "$measure_end_json"
  stop_api
}

write_capacity_result() {
  local safe
  safe="$(python3 - "$SUMMARY_CSV" <<'PY'
import csv
import sys

summary = sys.argv[1]
safe = None

with open(summary, newline='') as f:
    rows = list(csv.DictReader(f))

for row in rows:
    if row['passed'] == 'PASS':
        candidate = {
            'workers': int(row['workers']),
            'rate': int(row['rate']),
            'throughput': float(row['throughput_jobs_per_sec']),
            'efficiency': float(row['efficiency']),
            'failed_rate': float(row['failed_rate']),
            'backlog_growth': int(float(row['backlog_growth'])),
        }
        if safe is None or candidate['rate'] > safe['rate']:
            safe = candidate

if safe is None:
    print('NONE')
else:
    print(f"workers={safe['workers']} rate={safe['rate']} throughput={safe['throughput']:.4f} efficiency={safe['efficiency']:.4f} failed_rate={safe['failed_rate']:.6f} backlog_growth={safe['backlog_growth']}")
PY
)"

  local result_file="$RESULTS_DIR/capacity-result.txt"
  if [[ "$safe" == "NONE" ]]; then
    echo "Nenhum ponto aprovado pelos SLOs configurados." >"$result_file"
    log "Nenhum ponto aprovado pelos criterios configurados."
  else
    echo "Capacidade segura encontrada: $safe" >"$result_file"
    log "Capacidade segura encontrada: $safe"
  fi
}

write_capacity_report() {
  python3 - "$SUMMARY_CSV" "$REPORT_MD" "$MAX_BACKLOG_GROWTH" "$MAX_FAILED_RATE" "$MIN_EFFICIENCY" <<'PY'
import csv
import datetime
import sys

summary_path = sys.argv[1]
report_path = sys.argv[2]
max_backlog_growth = sys.argv[3]
max_failed_rate = sys.argv[4]
min_efficiency = sys.argv[5]

rows = []
with open(summary_path, newline='') as file:
  rows = list(csv.DictReader(file))

safe = None
for row in rows:
  if row['passed'] != 'PASS':
    continue
  candidate = {
    'workers': int(row['workers']),
    'rate': int(row['rate']),
    'throughput': float(row['throughput_jobs_per_sec']),
    'efficiency': float(row['efficiency']),
    'failed_rate': float(row['failed_rate']),
    'backlog_growth': int(float(row['backlog_growth'])),
  }
  if safe is None or candidate['rate'] > safe['rate']:
    safe = candidate

with open(report_path, 'w', encoding='ascii') as out:
  out.write('# Capacity Benchmark Report\n\n')
  out.write(f'Generated at: {datetime.datetime.now(datetime.timezone.utc).isoformat()}\n\n')
  out.write('## Acceptance Rules\n\n')
  out.write(f'- max_backlog_growth: <= {max_backlog_growth}\n')
  out.write(f'- max_failed_rate: <= {max_failed_rate}\n')
  out.write(f'- min_efficiency: >= {min_efficiency}\n\n')

  out.write('## Results\n\n')
  out.write('| workers | rate | throughput_jobs_per_sec | efficiency | backlog_growth | failed_rate | passed |\n')
  out.write('|---:|---:|---:|---:|---:|---:|:---|\n')
  for row in rows:
    out.write(
      f"| {row['workers']} | {row['rate']} | {row['throughput_jobs_per_sec']} | {row['efficiency']} | "
      f"{row['backlog_growth']} | {row['failed_rate']} | {row['passed']} |\n"
    )

  out.write('\n## Conclusion\n\n')
  if safe is None:
    out.write('No test point passed the configured acceptance rules.\n')
  else:
    out.write(
      'Safe capacity found: '
      f"workers={safe['workers']}, rate={safe['rate']} jobs/s, "
      f"throughput={safe['throughput']:.4f} jobs/s, "
      f"efficiency={safe['efficiency']:.4f}, "
      f"failed_rate={safe['failed_rate']:.6f}, "
      f"backlog_growth={safe['backlog_growth']}.\n"
    )
PY
}

printf "workers,rate,throughput_jobs_per_sec,efficiency,backlog_growth,failed_rate,avg_wait_start,avg_wait_end,avg_proc_start,avg_proc_end,passed\n" >"$SUMMARY_CSV"
printf "timestamp,workers,rate,phase,elapsed_sec,pending,processing,retry,done,failed,active_workers,avg_wait_seconds,avg_processing_seconds\n" >"$SAMPLES_CSV"

log "Inicio do benchmark AI-first"
log "Resultados em: $RESULTS_DIR"

apply_migrations

for workers in $WORKER_THREADS_LIST; do
  for rate in $TARGET_RATES; do
    run_point "$workers" "$rate"
  done
done

write_capacity_result
write_capacity_report

log "Benchmark finalizado"
log "Resumo: $SUMMARY_CSV"
log "Amostras: $SAMPLES_CSV"
log "Resultado final: $RESULTS_DIR/capacity-result.txt"
log "Relatorio markdown: $REPORT_MD"
