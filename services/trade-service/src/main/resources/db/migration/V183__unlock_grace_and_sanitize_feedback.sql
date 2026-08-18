-- OBS-019：人工解锁宽限期时间戳
ALTER TABLE device_info
    ADD COLUMN IF NOT EXISTS sales_unlocked_at TIMESTAMPTZ;

COMMENT ON COLUMN device_info.sales_unlocked_at IS '最近一次人工/策略解锁时间；离线自动锁机宽限期内跳过';

-- OBS-020：清理反馈联系方式中的 XSS 样例载荷
UPDATE user_feedback
SET contact_info = NULL
WHERE contact_info IS NOT NULL
  AND contact_info ~* '(<|>|javascript:|onerror\s*=|onload\s*=|<script)';
