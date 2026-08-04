ALTER TABLE dispute_ticket ADD COLUMN IF NOT EXISTS category VARCHAR(32) NOT NULL DEFAULT 'RECOGNITION';
ALTER TABLE dispute_ticket ADD COLUMN IF NOT EXISTS priority VARCHAR(16) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE dispute_ticket ADD COLUMN IF NOT EXISTS operator_note VARCHAR(512);
ALTER TABLE dispute_ticket ADD COLUMN IF NOT EXISTS closed_at TIMESTAMPTZ;
ALTER TABLE dispute_ticket ADD COLUMN IF NOT EXISTS reopened_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_dispute_status_priority ON dispute_ticket (status, priority, created_at DESC);
