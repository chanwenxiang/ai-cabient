-- 附件内容哈希：用于上传去重（同一图片只存一份对象）与后续孤儿清理

ALTER TABLE file_attachment ADD COLUMN IF NOT EXISTS content_sha256 VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_file_attachment_content_sha
    ON file_attachment (content_sha256);
