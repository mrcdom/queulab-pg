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
  ON job_queue (status, available_at, id)
  WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX idx_job_queue_queue_pick
  ON job_queue (queue_name, status, available_at, id)
  WHERE status IN ('PENDING', 'RETRY');

CREATE INDEX idx_job_queue_stuck
  ON job_queue (status, locked_at)
  WHERE status = 'PROCESSING';

CREATE TABLE worker_registry (
  worker_id TEXT PRIMARY KEY,
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_heartbeat_at TIMESTAMPTZ NOT NULL,
  status TEXT NOT NULL,
  processed_count BIGINT NOT NULL DEFAULT 0,
  failed_count BIGINT NOT NULL DEFAULT 0
);

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

CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS trigger AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION notify_job_queue()
RETURNS trigger AS $$
BEGIN
  IF NEW.status IN ('PENDING', 'RETRY') THEN
    PERFORM pg_notify('job_queue_new', NEW.queue_name);
  END IF;
  RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_job_queue_updated_at
BEFORE UPDATE ON job_queue
FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_notify_job_queue_insert
AFTER INSERT ON job_queue
FOR EACH ROW
EXECUTE FUNCTION notify_job_queue();

CREATE TRIGGER trg_notify_job_queue_retry
AFTER UPDATE OF status, available_at ON job_queue
FOR EACH ROW
WHEN (NEW.status IN ('PENDING', 'RETRY') AND NEW.available_at <= NOW())
EXECUTE FUNCTION notify_job_queue();