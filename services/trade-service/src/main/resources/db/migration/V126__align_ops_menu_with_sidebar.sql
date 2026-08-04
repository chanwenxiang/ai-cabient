-- V126: 运营侧栏与菜单管理树对齐；停用商业化中心；修正新菜单挂载与路由

-- 1) 一级目录：与侧栏一致（概览 / 交易履约 / 设备商品 / 履约仓储 / 财务商户 / 增长风控 / 系统）
UPDATE ops_permission SET perm_name = '概览', sort_order = 1, status = 'ACTIVE'
WHERE perm_code = 'ops:nav:overview';

UPDATE ops_permission SET perm_name = '交易履约', sort_order = 2, status = 'ACTIVE'
WHERE perm_code = 'ops:nav:biz';

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 470, permission_id, 'ops:nav:device', '设备商品', 'M', NULL, 3, 'ACTIVE'
FROM ops_permission WHERE perm_code = 'ops'
ON CONFLICT (perm_code) DO UPDATE SET
  perm_name = EXCLUDED.perm_name, parent_id = EXCLUDED.parent_id,
  sort_order = EXCLUDED.sort_order, status = 'ACTIVE';

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 471, permission_id, 'ops:nav:fulfill', '履约仓储', 'M', NULL, 4, 'ACTIVE'
FROM ops_permission WHERE perm_code = 'ops'
ON CONFLICT (perm_code) DO UPDATE SET
  perm_name = EXCLUDED.perm_name, parent_id = EXCLUDED.parent_id,
  sort_order = EXCLUDED.sort_order, status = 'ACTIVE';

UPDATE ops_permission SET perm_name = '财务商户', sort_order = 5, status = 'ACTIVE'
WHERE perm_code = 'ops:nav:ops';

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 472, permission_id, 'ops:nav:growth', '增长风控', 'M', NULL, 6, 'ACTIVE'
FROM ops_permission WHERE perm_code = 'ops'
ON CONFLICT (perm_code) DO UPDATE SET
  perm_name = EXCLUDED.perm_name, parent_id = EXCLUDED.parent_id,
  sort_order = EXCLUDED.sort_order, status = 'ACTIVE';

UPDATE ops_permission SET perm_name = '系统', sort_order = 7, status = 'ACTIVE'
WHERE perm_code = 'ops:nav:sys';

-- 2) 停用商业化中心
UPDATE ops_permission
SET status = 'INACTIVE', perm_name = '商业化中心(已下线)'
WHERE perm_code = 'ops:commercial-hub:list';

-- 3) 挂载与命名对齐侧栏（概览）
UPDATE ops_permission SET parent_id = 400, sort_order = 10, path = '/dashboard', perm_name = '运营工作台'
WHERE perm_code = 'ops:dashboard:view';
UPDATE ops_permission SET parent_id = 400, sort_order = 20, path = '/analytics', perm_name = '数据分析'
WHERE perm_code = 'ops:analytics:view';
UPDATE ops_permission SET parent_id = 400, sort_order = 30, path = '/reports', perm_name = '设备报表'
WHERE perm_code = 'ops:report:device';
UPDATE ops_permission SET parent_id = 400, sort_order = 40, path = '/finance', perm_name = '财务毛利'
WHERE perm_code = 'ops:finance:view';
UPDATE ops_permission SET parent_id = 400, sort_order = 50, path = '/sales-reports', perm_name = '销售报表'
WHERE perm_code = 'ops:sales-report:list';

-- 交易履约（原 ops:nav:biz）
UPDATE ops_permission SET parent_id = 401, sort_order = 10, path = '/orders', perm_name = '订单管理'
WHERE perm_code = 'ops:order:list';
UPDATE ops_permission SET parent_id = 401, sort_order = 20, path = '/sessions', perm_name = '开门记录'
WHERE perm_code = 'ops:session:list';
UPDATE ops_permission SET parent_id = 401, sort_order = 30, path = '/disputes', perm_name = '争议审核'
WHERE perm_code = 'ops:dispute';
UPDATE ops_permission SET parent_id = 401, sort_order = 40, path = '/exceptions', perm_name = '异常中心'
WHERE perm_code = 'ops:exception:list';

-- 设备商品
UPDATE ops_permission SET parent_id = 470, sort_order = 10, path = '/device-ops', perm_name = '设备运维'
WHERE perm_code = 'ops:device-ops:list';
UPDATE ops_permission SET parent_id = 470, sort_order = 20, path = '/devices', perm_name = '设备管理'
WHERE perm_code = 'ops:device:list';
UPDATE ops_permission SET parent_id = 470, sort_order = 30, path = '/skus', perm_name = '商品与识别'
WHERE perm_code = 'ops:sku:list';
UPDATE ops_permission SET parent_id = 470, sort_order = 40, path = '/vision-mappings', perm_name = '识别映射'
WHERE perm_code = 'ops:vision:list';
UPDATE ops_permission SET parent_id = 470, sort_order = 50, path = '/upload-queue', perm_name = '录像上传'
WHERE perm_code = 'ops:session:upload';

-- 履约仓储
UPDATE ops_permission SET parent_id = 471, sort_order = 10, path = '/replenishment', perm_name = '补货调度'
WHERE perm_code = 'ops:replenishment:list';
UPDATE ops_permission SET parent_id = 471, sort_order = 20, path = '/warehouse', perm_name = '仓库'
WHERE perm_code = 'ops:warehouse:list';
UPDATE ops_permission SET parent_id = 471, sort_order = 30, path = '/ota', perm_name = '固件版本'
WHERE perm_code = 'ops:ota:list';
UPDATE ops_permission SET parent_id = 471, sort_order = 40, path = '/sla', perm_name = '服务时限监控'
WHERE perm_code = 'ops:sla';

-- 财务商户（原 ops:nav:ops）
UPDATE ops_permission SET parent_id = 402, sort_order = 10, path = '/fund-bills', perm_name = '资金账单'
WHERE perm_code = 'ops:fund:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 20, path = '/merchants', perm_name = '商户与分账'
WHERE perm_code = 'ops:merchant:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 30, path = '/reconciliation', perm_name = '对账'
WHERE perm_code = 'ops:reconciliation:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 40, path = '/consistency', perm_name = '数据一致性'
WHERE perm_code = 'ops:consistency:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 50, path = '/recharges', perm_name = '充值管理'
WHERE perm_code = 'ops:recharge:list';
UPDATE ops_permission SET parent_id = 402, sort_order = 60, path = '/users', perm_name = '用户余额'
WHERE perm_code = 'ops:user:list';

-- 增长风控
UPDATE ops_permission SET parent_id = 472, sort_order = 10, path = '/phone-verify', perm_name = '手机验证'
WHERE perm_code = 'ops:phone-verify:list';
UPDATE ops_permission SET parent_id = 472, sort_order = 20, path = '/risk', perm_name = '风控'
WHERE perm_code = 'ops:risk:list';
UPDATE ops_permission SET parent_id = 472, sort_order = 30, path = '/promotions', perm_name = '营销活动'
WHERE perm_code = 'ops:promotion:list';
UPDATE ops_permission SET parent_id = 472, sort_order = 40, path = '/coupons', perm_name = '优惠券'
WHERE perm_code = 'ops:coupon:list';
UPDATE ops_permission SET parent_id = 472, sort_order = 50, path = '/feedback', perm_name = '用户反馈'
WHERE perm_code = 'ops:feedback';

-- 系统
UPDATE ops_permission SET parent_id = 403, sort_order = 10, path = '/operators', perm_name = '运营账号'
WHERE perm_code = 'ops:rbac:assign';
UPDATE ops_permission SET parent_id = 403, sort_order = 20, path = '/roles', perm_name = '角色管理'
WHERE perm_code = 'ops:rbac:role';
UPDATE ops_permission SET parent_id = 403, sort_order = 30, path = '/menus', perm_name = '菜单管理'
WHERE perm_code = 'ops:rbac:menu';
UPDATE ops_permission SET parent_id = 403, sort_order = 40, path = '/dicts', perm_name = '字典管理'
WHERE perm_code = 'ops:dict:list';
UPDATE ops_permission SET parent_id = 403, sort_order = 50, path = '/system-configs', perm_name = '参数配置'
WHERE perm_code = 'ops:config:list';
UPDATE ops_permission SET parent_id = 403, sort_order = 60, path = '/announcements', perm_name = '通知公告'
WHERE perm_code = 'ops:announcement:list';
UPDATE ops_permission SET parent_id = 403, sort_order = 70, path = '/audit', perm_name = '审计日志'
WHERE perm_code = 'ops:audit:list';

-- 4) 新目录授权（有对应业务权的角色 + 超管）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_new
JOIN ops_permission p_gate ON p_gate.perm_code IN ('ops:nav:biz', 'ops:nav:ops', 'ops:admin')
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
WHERE p_new.perm_code IN ('ops:nav:device', 'ops:nav:fulfill', 'ops:nav:growth')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:nav:overview', 'ops:nav:biz', 'ops:nav:device', 'ops:nav:fulfill',
                    'ops:nav:ops', 'ops:nav:growth', 'ops:nav:sys')
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
