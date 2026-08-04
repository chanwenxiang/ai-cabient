-- 商户门户 P1：扩展权限、设备与商户自助设置字段

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (204, 200, 'merchant:orders:list',      '订单查询',     'C', '/merchant/orders',      4),
    (205, 200, 'merchant:devices:detail',   '柜机详情',     'C', '/merchant/devices',     5),
    (206, 200, 'merchant:devices:edit',     '柜机设置',     'C', '/merchant/devices',     6),
    (207, 200, 'merchant:alerts:view',      '运营告警',     'C', '/merchant/alerts',      7),
    (208, 200, 'merchant:inventory:view',   '库存健康',     'C', '/merchant/inventory',   8),
    (209, 200, 'merchant:disputes:list',    '异常工单',     'C', '/merchant/disputes',    9),
    (210, 200, 'merchant:reports:view',     '经营报表',     'C', '/merchant/reports',    10),
    (211, 200, 'merchant:profile:edit',     '商户设置',     'C', '/merchant/settings',   11),
    (212, 200, 'merchant:trend:view',       '经营趋势',     'C', '/merchant/dashboard',  12)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code LIKE 'merchant:%'
ON CONFLICT DO NOTHING;

-- 设备级商户可配置项（展示名、告警联系人、目标温度、备注）
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS alert_contact_name  VARCHAR(64);
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS alert_contact_phone VARCHAR(32);
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS target_temp_c       INT;
ALTER TABLE device_info ADD COLUMN IF NOT EXISTS ops_remark          VARCHAR(256);

-- 商户级告警联系人（可覆盖设备级默认）
ALTER TABLE merchant ADD COLUMN IF NOT EXISTS alert_contact_name  VARCHAR(64);
ALTER TABLE merchant ADD COLUMN IF NOT EXISTS alert_contact_phone VARCHAR(32);
