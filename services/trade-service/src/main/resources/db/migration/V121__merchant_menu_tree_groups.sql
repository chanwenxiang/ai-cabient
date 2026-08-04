-- 商户门户权限：补二级目录，并将「编辑/导出/详情」等动作改为按钮 F

-- 二级目录（挂在 商户门户 merchant=200 下）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
VALUES
    (451, 200, 'merchant:nav:field', '现场作业', 'M', NULL, 10, 'ACTIVE'),
    (452, 200, 'merchant:nav:biz',   '经营工具', 'M', NULL, 20, 'ACTIVE'),
    (453, 200, 'merchant:nav:team',  '团队与设置', 'M', NULL, 30, 'ACTIVE')
ON CONFLICT (perm_code) DO NOTHING;

-- 门户访问仍挂在根下
UPDATE ops_permission SET parent_id = 200, sort_order = 1, perm_type = 'C'
WHERE perm_code = 'merchant:portal:access';

-- 现场作业
UPDATE ops_permission SET parent_id = 451, sort_order = 10, perm_type = 'C', path = '/merchant/devices'
WHERE perm_code = 'merchant:devices:list';
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:devices:list'),
                   sort_order = 11, perm_type = 'F', path = NULL
WHERE perm_code = 'merchant:devices:detail';
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:devices:list'),
                   sort_order = 12, perm_type = 'F', path = NULL
WHERE perm_code = 'merchant:devices:edit';
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:devices:list'),
                   sort_order = 13, perm_type = 'F', path = NULL
WHERE perm_code = 'merchant:slots:view';
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:devices:list'),
                   sort_order = 14, perm_type = 'F', path = NULL
WHERE perm_code = 'merchant:slots:edit';
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:devices:list'),
                   sort_order = 15, perm_type = 'F', path = NULL
WHERE perm_code = 'merchant:temp:history';

UPDATE ops_permission SET parent_id = 451, sort_order = 20, perm_type = 'C', path = '/merchant/replenishment'
WHERE perm_code = 'merchant:replenishment:view';
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:replenishment:view'),
                   sort_order = 21, perm_type = 'F', path = NULL
WHERE perm_code = 'merchant:replenishment:request';

UPDATE ops_permission SET parent_id = 451, sort_order = 30, perm_type = 'C', path = '/merchant/alerts'
WHERE perm_code = 'merchant:alerts:view';
UPDATE ops_permission SET parent_id = 451, sort_order = 40, perm_type = 'C', path = '/merchant/inventory'
WHERE perm_code = 'merchant:inventory:view';

-- 经营工具
UPDATE ops_permission SET parent_id = 452, sort_order = 10, perm_type = 'C', path = '/merchant/orders'
WHERE perm_code = 'merchant:orders:list';
UPDATE ops_permission SET parent_id = 452, sort_order = 20, perm_type = 'C', path = '/merchant/splits'
WHERE perm_code = 'merchant:splits:list';

UPDATE ops_permission SET parent_id = 452, sort_order = 30, perm_type = 'C', path = '/merchant/settlements'
WHERE perm_code = 'merchant:settlements:view';
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:settlements:view'),
                   sort_order = 31, perm_type = 'F', path = NULL
WHERE perm_code = 'merchant:settlements:export';

UPDATE ops_permission SET parent_id = 452, sort_order = 40, perm_type = 'C', path = '/merchant/pricing'
WHERE perm_code = 'merchant:pricing:view';
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:pricing:view'),
                   sort_order = 41, perm_type = 'F', path = NULL
WHERE perm_code = 'merchant:pricing:edit';

UPDATE ops_permission SET parent_id = 452, sort_order = 50, perm_type = 'C', path = '/merchant/disputes'
WHERE perm_code = 'merchant:disputes:list';
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:disputes:list'),
                   sort_order = 51, perm_type = 'F', path = NULL
WHERE perm_code = 'merchant:disputes:reply';

UPDATE ops_permission SET parent_id = 452, sort_order = 60, perm_type = 'C', path = '/merchant/reports'
WHERE perm_code = 'merchant:reports:view';
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:reports:view'),
                   sort_order = 61, perm_type = 'F', path = NULL
WHERE perm_code = 'merchant:reports:export';

UPDATE ops_permission SET parent_id = 452, sort_order = 70, perm_type = 'C', path = '/merchant/dashboard'
WHERE perm_code = 'merchant:trend:view';
UPDATE ops_permission SET parent_id = 452, sort_order = 80, perm_type = 'C', path = '/merchant/analytics'
WHERE perm_code = 'merchant:analytics:view';
UPDATE ops_permission SET parent_id = 452, sort_order = 90, perm_type = 'C', path = '/merchant/coupons'
WHERE perm_code = 'merchant:coupon:view';

-- 团队与设置
UPDATE ops_permission SET parent_id = 453, sort_order = 10, perm_type = 'C', path = '/merchant/settings'
WHERE perm_code = 'merchant:profile:edit';
UPDATE ops_permission SET parent_id = 453, sort_order = 20, perm_type = 'C', path = '/merchant/team'
WHERE perm_code = 'merchant:users:list';
UPDATE ops_permission SET parent_id = (SELECT permission_id FROM ops_permission WHERE perm_code = 'merchant:users:list'),
                   sort_order = 21, perm_type = 'F', path = NULL
WHERE perm_code = 'merchant:users:invite';

-- 已有商户权限的角色自动挂上新二级目录（及根目录）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p.permission_id
FROM ops_role_permission rp
JOIN ops_permission child ON child.permission_id = rp.permission_id
JOIN ops_permission p ON p.perm_code IN ('merchant', 'merchant:nav:field', 'merchant:nav:biz', 'merchant:nav:team')
WHERE child.perm_code LIKE 'merchant:%'
ON CONFLICT DO NOTHING;
