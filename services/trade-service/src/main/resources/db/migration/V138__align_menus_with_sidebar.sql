-- V138: 菜单管理树与侧栏 menu.ts 对齐（设备组排序 + 识别演示 C 菜单）

-- 设备商品叶子顺序：运维 → 管理 → 地图 → 维修 → 商品 → 映射 → 上传
UPDATE ops_permission SET parent_id = 470, sort_order = 10, path = '/device-ops', perm_name = '设备运维', status = 'ACTIVE'
WHERE perm_code = 'ops:device-ops:list';
UPDATE ops_permission SET parent_id = 470, sort_order = 20, path = '/devices', perm_name = '设备管理', status = 'ACTIVE'
WHERE perm_code = 'ops:device:list';
UPDATE ops_permission SET parent_id = 470, sort_order = 30, path = '/device-map', perm_name = '投放地图', status = 'ACTIVE'
WHERE perm_code = 'ops:device-map:view';
UPDATE ops_permission SET parent_id = 470, sort_order = 40, path = '/repair-tickets', perm_name = '维修工单', status = 'ACTIVE'
WHERE perm_code = 'ops:repair:list';
UPDATE ops_permission SET parent_id = 470, sort_order = 50, path = '/skus', perm_name = '商品与识别', status = 'ACTIVE'
WHERE perm_code = 'ops:sku:list';
UPDATE ops_permission SET parent_id = 470, sort_order = 60, path = '/vision-mappings', perm_name = '识别映射', status = 'ACTIVE'
WHERE perm_code = 'ops:vision:list';
UPDATE ops_permission SET parent_id = 470, sort_order = 70, path = '/upload-queue', perm_name = '录像上传', status = 'ACTIVE'
WHERE perm_code = 'ops:session:upload';

-- 识别演示：改为 C 级菜单，才能进入 activeNavPerms（侧栏可见；按钮仍用 ops:sku:demo）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
VALUES (473, 470, 'ops:recognition-demo:view', '识别演示', 'C', '/recognition-demo', 55, 'ACTIVE')
ON CONFLICT (perm_code) DO UPDATE SET
  parent_id = EXCLUDED.parent_id,
  perm_name = EXCLUDED.perm_name,
  perm_type = 'C',
  path = EXCLUDED.path,
  sort_order = EXCLUDED.sort_order,
  status = 'ACTIVE';

-- 给已有 sku:demo 或 vision/sku 编辑权限的角色补演示菜单
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p.permission_id
FROM ops_role_permission rp
JOIN ops_permission src ON src.permission_id = rp.permission_id
JOIN ops_permission p ON p.perm_code = 'ops:recognition-demo:view'
WHERE src.perm_code IN ('ops:sku:demo', 'ops:sku:list', 'ops:vision:list', 'ops:admin')
ON CONFLICT DO NOTHING;

-- 概览：库存健康挂到概览组
UPDATE ops_permission SET parent_id = 400, sort_order = 60, path = '/stock-health', perm_name = '库存健康', status = 'ACTIVE'
WHERE perm_code = 'ops:stock-health:list';

-- 财务商户：线长钱包
UPDATE ops_permission SET parent_id = 402, sort_order = 25, path = '/line-managers', perm_name = '线长钱包', status = 'ACTIVE'
WHERE perm_code = 'ops:line-manager:list';
