-- Fix system operator (user_id=0) display name corrupted when UTF-8「系统」
-- was mis-decoded as GBK (shows as「绯荤粺」). Use hex UTF-8 so this migration
-- is encoding-safe on Windows Flyway clients.

UPDATE user_info
SET name = convert_from(decode('e7b3bbe7bb9f', 'hex'), 'UTF8'),
    updated_at = now()
WHERE user_id = 0
  AND name = convert_from(decode('e7bbafe88da4e7b2ba', 'hex'), 'UTF8');
