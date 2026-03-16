ALTER TABLE job_queue
  ADD COLUMN IF NOT EXISTS exchange_name TEXT,
  ADD COLUMN IF NOT EXISTS routing_key TEXT,
  ADD COLUMN IF NOT EXISTS broker_message_id TEXT,
  ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS consumed_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS completed_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS message_outbox (
  outbox_id BIGSERIAL PRIMARY KEY,
  job_id BIGINT NOT NULL REFERENCES job_queue (id) ON DELETE CASCADE,
  exchange_name TEXT NOT NULL,
  routing_key TEXT NOT NULL,
  message_id TEXT NOT NULL UNIQUE,
  payload JSONB NOT NULL,
  status TEXT NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  published_at TIMESTAMPTZ,
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_message_outbox_pending
  ON message_outbox (status, next_attempt_at, outbox_id)
  WHERE status = 'PENDING';

CREATE OR REPLACE FUNCTION set_message_outbox_updated_at()
RETURNS trigger AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_message_outbox_updated_at ON message_outbox;

CREATE TRIGGER trg_message_outbox_updated_at
BEFORE UPDATE ON message_outbox
FOR EACH ROW
EXECUTE FUNCTION set_message_outbox_updated_at();
