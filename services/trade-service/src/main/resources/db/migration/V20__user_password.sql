-- 用户密码登录（BCrypt）

ALTER TABLE user_info ADD COLUMN IF NOT EXISTS password_hash VARCHAR(100);

-- 种子账号默认密码 123456（仅开发/预发；生产请修改）
UPDATE user_info
SET password_hash = '$2b$10$wtX2jX5K5h0IuUQxT2BAqOMT19ngpMGUDR76A3MdQcGnoCZzM6oFC'
WHERE user_id IN (10001, 100000001)
  AND (password_hash IS NULL OR password_hash = '');
