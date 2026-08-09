-- V161: 修复 V140 演示补货员账号 userId 冲突
-- V116 已占用 userId 100000007（财务演示 13900000002），V140 试图用同一 userId 建
-- 13800138007 补货员，被 ON CONFLICT (user_id) DO NOTHING 跳过；随后 V140 又把
-- merchant_replenisher 角色和 MCH-DEFAULT 绑定挂到了 100000007 上。
-- 结果：13800138007 不存在；财务演示账号意外获得商家补货员权限（角色并集）。
--
-- 修正方案：
--   1) 撤销 100000007 上误挂的 merchant_replenisher 角色与 MCH-DEFAULT 绑定；
--   2) 补货员改用未占用的 userId 100000011（13800138007 / 123456），绑定 MCH-DEFAULT。
-- 新库中步骤 1) 为无操作，两态（已迁移旧库 / 全新库）最终一致。

-- 1) 回滚 100000007（财务演示）误挂的商家补货员角色与商家绑定
DELETE FROM ops_user_role
WHERE user_id = 100000007
  AND role_id = (SELECT role_id FROM ops_role WHERE role_key = 'merchant_replenisher');

DELETE FROM ops_user_merchant
WHERE user_id = 100000007 AND merchant_id = 'MCH-DEFAULT';

-- 2) 新建演示补货员账号：13800138007 / 123456（hash 与 V116/V117/V140 演示账号一致）
INSERT INTO user_info (user_id, phone_number, name, verified, status)
VALUES (100000011, '13800138007', '演示补货员', TRUE, 'ACTIVE')
ON CONFLICT (user_id) DO UPDATE
SET phone_number = EXCLUDED.phone_number,
    name = EXCLUDED.name,
    verified = TRUE,
    status = 'ACTIVE';

INSERT INTO user_account (user_id, balance_cents)
VALUES (100000011, 0)
ON CONFLICT (user_id) DO NOTHING;

UPDATE user_info
SET password_hash = '$2b$10$wtX2jX5K5h0IuUQxT2BAqOMT19ngpMGUDR76A3MdQcGnoCZzM6oFC'
WHERE user_id = 100000011
  AND (password_hash IS NULL OR password_hash = '');

INSERT INTO ops_user_role (user_id, role_id)
SELECT 100000011, role_id FROM ops_role WHERE role_key = 'merchant_replenisher'
ON CONFLICT DO NOTHING;

INSERT INTO ops_user_merchant (user_id, merchant_id)
VALUES (100000011, 'MCH-DEFAULT')
ON CONFLICT DO NOTHING;
