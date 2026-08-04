-- 菜单树对齐运营侧栏分组（概览/业务/运营/系统），并修正残留名称

-- 1) 名称与侧栏一致
UPDATE ops_permission SET perm_name = '风控' WHERE perm_code = 'ops:risk:list';
UPDATE ops_permission SET perm_name = '对账' WHERE perm_code = 'ops:reconciliation:list';
UPDATE ops_permission SET perm_name = '补货' WHERE perm_code = 'ops:replenishment:list';
UPDATE ops_permission SET perm_name = '字典管理' WHERE perm_code = 'ops:dict:list';
UPDATE ops_permission SET perm_name = '仓库管理' WHERE perm_code = 'ops:warehouse' AND perm_type = 'M';

-- 2) 侧栏分组目录（挂在 ops 下）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 400, permission_id, 'ops:nav:overview', '概览', 'M', NULL, 1, 'ACTIVE'
FROM ops_permission WHERE perm_code = 'ops'
ON CONFLICT (perm_code) DO UPDATE SET perm_name = EXCLUDED.perm_name, status = 'ACTIVE', sort_order = 1;

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 401, permission_id, 'ops:nav:biz', '业务', 'M', NULL, 2, 'ACTIVE'
FROM ops_permission WHERE perm_code = 'ops'
ON CONFLICT (perm_code) DO UPDATE SET perm_name = EXCLUDED.perm_name, status = 'ACTIVE', sort_order = 2;

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 402, permission_id, 'ops:nav:ops', '运营', 'M', NULL, 3, 'ACTIVE'
FROM ops_permission WHERE perm_code = 'ops'
ON CONFLICT (perm_code) DO UPDATE SET perm_name = EXCLUDED.perm_name, status = 'ACTIVE', sort_order = 3;

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 403, permission_id, 'ops:nav:sys', '系统', 'M', NULL, 4, 'ACTIVE'
FROM ops_permission WHERE perm_code = 'ops'
ON CONFLICT (perm_code) DO UPDATE SET perm_name = EXCLUDED.perm_name, status = 'ACTIVE', sort_order = 4;

-- 3) C 菜单挂到对应分组（排序对齐侧栏）
UPDATE ops_permission SET parent_id = 400, sort_order = 10 WHERE perm_code = 'ops:dashboard:view';
UPDATE ops_permission SET parent_id = 400, sort_order = 20 WHERE perm_code = 'ops:analytics:view';
UPDATE ops_permission SET parent_id = 400, sort_order = 30 WHERE perm_code = 'ops:report:device';
UPDATE ops_permission SET parent_id = 400, sort_order = 40 WHERE perm_code = 'ops:finance:view';

UPDATE ops_permission SET parent_id = 401, sort_order = 10 WHERE perm_code = 'ops:device:list';
UPDATE ops_permission SET parent_id = 401, sort_order = 20 WHERE perm_code = 'ops:session:list';
UPDATE ops_permission SET parent_id = 401, sort_order = 30 WHERE perm_code = 'ops:session:upload';
UPDATE ops_permission SET parent_id = 401, sort_order = 40 WHERE perm_code = 'ops:order:list';
UPDATE ops_permission SET parent_id = 401, sort_order = 50 WHERE perm_code = 'ops:sku:list';
UPDATE ops_permission SET parent_id = 401, sort_order = 60 WHERE perm_code = 'ops:dispute';
UPDATE ops_permission SET parent_id = 401, sort_order = 70 WHERE perm_code = 'ops:exception:list';

UPDATE ops_permission SET parent_id = 402, sort_order = 10 WHERE perm_code = 'ops:replenishment:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 20 WHERE perm_code = 'ops:merchant:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 30 WHERE perm_code = 'ops:reconciliation:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 40 WHERE perm_code = 'ops:warehouse:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 50 WHERE perm_code = 'ops:recharge:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 60 WHERE perm_code = 'ops:user:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 70 WHERE perm_code = 'ops:risk:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 80 WHERE perm_code = 'ops:promotion:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 90 WHERE perm_code = 'ops:coupon:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 100 WHERE perm_code = 'ops:feedback';

UPDATE ops_permission SET parent_id = 403, sort_order = 10 WHERE perm_code = 'ops:rbac:assign';
UPDATE ops_permission SET parent_id = 403, sort_order = 20 WHERE perm_code = 'ops:rbac:role';
UPDATE ops_permission SET parent_id = 403, sort_order = 30 WHERE perm_code = 'ops:rbac:menu';
UPDATE ops_permission SET parent_id = 403, sort_order = 40 WHERE perm_code = 'ops:dict:list';
UPDATE ops_permission SET parent_id = 403, sort_order = 50 WHERE perm_code = 'ops:config:list';
UPDATE ops_permission SET parent_id = 403, sort_order = 60 WHERE perm_code = 'ops:announcement:list';
UPDATE ops_permission SET parent_id = 403, sort_order = 70 WHERE perm_code = 'ops:audit:list';
UPDATE ops_permission SET parent_id = 403, sort_order = 80 WHERE perm_code = 'ops:operlog';

-- 4) 停用旧目录节点（侧栏已由 ops:nav:* 分组表达）
UPDATE ops_permission SET status = 'INACTIVE'
WHERE perm_type = 'M'
  AND perm_code IN (
    'ops:device', 'ops:session', 'ops:order', 'ops:sku', 'ops:user', 'ops:risk',
    'ops:reconciliation', 'ops:replenishment', 'ops:rbac', 'ops:audit', 'ops:merchant',
    'ops:dict', 'ops:exception', 'ops:warehouse', 'ops:recharge', 'ops:promotion',
    'ops:announcement', 'ops:config'
  );

-- 5) 分组目录授予超级管理员
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:nav:overview', 'ops:nav:biz', 'ops:nav:ops', 'ops:nav:sys')
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
