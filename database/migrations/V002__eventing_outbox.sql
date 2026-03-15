ALTER TABLE job_queue
  ADD COLUMN IF NOT EXISTS job_version BIGINT NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_job_queue_version
  ON job_queue (id, job_version);

CREATE TABLE IF NOT EXISTS event_outbox (
  outbox_id BIGSERIAL PRIMARY KEY,
  event_id TEXT NOT NULL UNIQUE,
  aggregate_type TEXT NOT NULL,
  aggregate_id BIGINT NOT NULL,
  aggregate_version BIGINT NOT NULL,
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

CREATE INDEX IF NOT EXISTS idx_event_outbox_pending
  ON event_outbox (status, next_attempt_at, outbox_id)
  WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_event_outbox_cursor
  ON event_outbox (outbox_id, created_at);

CREATE OR REPLACE FUNCTION set_outbox_updated_at()
RETURNS trigger AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_event_outbox_updated_at ON event_outbox;

CREATE TRIGGER trg_event_outbox_updated_at
BEFORE UPDATE ON event_outbox
FOR EACH ROW
EXECUTE FUNCTION set_outbox_updated_at();
