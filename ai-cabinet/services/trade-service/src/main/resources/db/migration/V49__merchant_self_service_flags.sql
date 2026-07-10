-- 商户自助开关：货道 planogram / 点位定价（平台控制，默认关闭）
ALTER TABLE merchant ADD COLUMN IF NOT EXISTS allow_merchant_planogram_edit BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE merchant ADD COLUMN IF NOT EXISTS allow_merchant_pricing_edit BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
VALUES
  (225, 200, 'merchant:slots:view', '货道查看', 'C', '/merchant/slots', 25),
  (226, 200, 'merchant:slots:edit', '货道编辑', 'C', '/merchant/slots', 26)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code IN ('merchant:slots:view', 'merchant:slots:edit')
ON CONFLICT DO NOTHING;
