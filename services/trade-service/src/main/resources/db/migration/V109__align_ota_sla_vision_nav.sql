-- 识别映射 / OTA / SLA 菜单路径与侧栏分组对齐

-- 识别映射：独立 C 菜单挂到业务分组，路径对齐前端
UPDATE ops_permission
SET parent_id = 401,
    sort_order = 55,
    path = '/admin/vision-mappings',
    perm_name = '识别映射',
    status = 'ACTIVE'
WHERE perm_code = 'ops:vision:list';

-- OTA 列表挂到运营分组
UPDATE ops_permission
SET parent_id = 402,
    sort_order = 110,
    path = '/admin/ota',
    perm_name = 'OTA 版本',
    status = 'ACTIVE'
WHERE perm_code = 'ops:ota:list';

-- SLA 挂到运营分组
UPDATE ops_permission
SET parent_id = 402,
    sort_order = 120,
    path = '/admin/sla',
    perm_name = 'SLA 监控',
    status = 'ACTIVE'
WHERE perm_code = 'ops:sla';

-- 旧 OTA 目录节点停用（侧栏直接用 C 菜单）
UPDATE ops_permission
SET status = 'INACTIVE'
WHERE perm_code = 'ops:ota' AND perm_type = 'M';

-- 确保超管拥有上述菜单
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:vision:list', 'ops:ota:list', 'ops:sla', 'ops:ota:publish')
ON CONFLICT DO NOTHING;
