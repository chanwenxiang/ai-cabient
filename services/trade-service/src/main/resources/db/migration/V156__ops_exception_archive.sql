-- 异常中心：已解决异常可归档，避免历史记录淹没待办
ALTER TABLE ops_exception ADD COLUMN IF NOT EXISTS archived BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ops_exception ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_ops_exception_archived_created
    ON ops_exception (archived, created_at DESC);
