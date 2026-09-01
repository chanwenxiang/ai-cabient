-- 运营个人中心：邮箱（头像 avatar_url 已在 V67 存在）
ALTER TABLE user_info
    ADD COLUMN IF NOT EXISTS email VARCHAR(128);

COMMENT ON COLUMN user_info.email IS U&'\90ae\7bb1';
