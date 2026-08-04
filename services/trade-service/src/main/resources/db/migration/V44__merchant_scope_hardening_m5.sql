-- M5 架构加固：第二租户种子，用于跨商户数据隔离验证

INSERT INTO merchant (merchant_id, merchant_name, platform_rate_bps, remark)
VALUES ('MCH-OTHER', '演示商户B', 1500, 'M5 跨租户隔离演示')
ON CONFLICT (merchant_id) DO NOTHING;

INSERT INTO device_info (device_id, device_name, device_type, online_status, merchant_id)
VALUES ('CAB-OTHER', '测试柜-OTHER', 'AI_CABINET_V1', 'OFFLINE', 'MCH-OTHER')
ON CONFLICT (device_id) DO NOTHING;

-- 演示账号：13800138003 / 123456，绑定 MCH-OTHER
INSERT INTO user_info (user_id, phone_number, name, verified)
VALUES (100000004, '13800138003', '商户B管理员', TRUE)
ON CONFLICT (user_id) DO NOTHING;

INSERT INTO user_account (user_id, balance_cents)
VALUES (100000004, 0)
ON CONFLICT (user_id) DO NOTHING;

UPDATE user_info
SET password_hash = '$2b$10$wtX2jX5K5h0IuUQxT2BAqOMT19ngpMGUDR76A3MdQcGnoCZzM6oFC'
WHERE user_id = 100000004
  AND (password_hash IS NULL OR password_hash = '');

INSERT INTO ops_user_role (user_id, role_id) VALUES (100000004, 6)
ON CONFLICT DO NOTHING;

INSERT INTO ops_user_merchant (user_id, merchant_id) VALUES (100000004, 'MCH-OTHER')
ON CONFLICT DO NOTHING;
