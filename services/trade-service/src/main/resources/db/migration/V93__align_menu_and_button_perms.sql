-- 对齐运营后台侧栏路由，停用无页孤儿菜单，补齐按钮权限（F）
-- 1) 路径统一为 Vue hash 路由（去掉 /admin 前缀）
UPDATE ops_permission SET path = REPLACE(path, '/admin', '')
WHERE path LIKE '/admin/%';

UPDATE ops_permission SET perm_name = '运营工作台' WHERE perm_code = 'ops:dashboard:view';
UPDATE ops_permission SET perm_name = '设备管理' WHERE perm_code = 'ops:device:list';
UPDATE ops_permission SET perm_name = '开门记录' WHERE perm_code = 'ops:session:list';
UPDATE ops_permission SET perm_name = '录像上传' WHERE perm_code = 'ops:session:upload';
UPDATE ops_permission SET perm_name = '订单管理' WHERE perm_code = 'ops:order:list';
UPDATE ops_permission SET perm_name = '商品与识别' WHERE perm_code = 'ops:sku:list';
UPDATE ops_permission SET perm_name = '灰度用户' WHERE perm_code = 'ops:user:list';
UPDATE ops_permission SET perm_name = '财务毛利' WHERE perm_code = 'ops:finance:view';
UPDATE ops_permission SET perm_name = '商户分账' WHERE perm_code = 'ops:merchant:list';
UPDATE ops_permission SET perm_name = '营销活动' WHERE perm_code = 'ops:promotion:list';
UPDATE ops_permission SET perm_name = '优惠券' WHERE perm_code = 'ops:coupon:list';
UPDATE ops_permission SET perm_name = '通知公告' WHERE perm_code = 'ops:announcement:list';
UPDATE ops_permission SET perm_name = '参数配置' WHERE perm_code = 'ops:config:list';
UPDATE ops_permission SET perm_name = '操作日志' WHERE perm_code = 'ops:operlog';
UPDATE ops_permission SET perm_name = '审计日志' WHERE perm_code = 'ops:audit:list';
UPDATE ops_permission SET perm_name = '设备报表' WHERE perm_code = 'ops:report:device';
UPDATE ops_permission SET perm_name = '异常中心' WHERE perm_code = 'ops:exception:list';
UPDATE ops_permission SET perm_name = '仓库' WHERE perm_code = 'ops:warehouse:list';
UPDATE ops_permission SET perm_name = '充值管理' WHERE perm_code = 'ops:recharge:list';

-- 2) 停用后台已无对应页面的孤儿菜单（仍保留记录便于审计）
UPDATE ops_permission SET status = 'INACTIVE'
WHERE perm_code IN (
    'ops:ota', 'ops:ota:list', 'ops:ota:publish',
    'ops:sla',
    'ops:vision', 'ops:vision:list', 'ops:vision:edit',
    'ops:merchant:split',
    'ops:audit:recent',
    'ops:message:templates', 'ops:message:templates:edit'
);

-- 3) 补齐按钮权限（对齐页面操作）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
VALUES
    -- 角色
    (310, 131, 'ops:rbac:role:add', '新增角色', 'F', NULL, 10),
    (311, 131, 'ops:rbac:role:edit', '编辑角色', 'F', NULL, 11),
    (312, 131, 'ops:rbac:role:perm', '分配权限', 'F', NULL, 12),
    -- 菜单 CRUD
    (313, 133, 'ops:rbac:menu:add', '新增菜单', 'F', NULL, 10),
    (314, 133, 'ops:rbac:menu:edit', '编辑菜单', 'F', NULL, 11),
    (315, 133, 'ops:rbac:menu:remove', '删除菜单', 'F', NULL, 12),
    -- 运营账号
    (316, 132, 'ops:rbac:assign:role', '分配角色', 'F', NULL, 10),
    (317, 132, 'ops:rbac:assign:merchant', '商户范围', 'F', NULL, 11),
    -- 争议
    (318, 70, 'ops:dispute:resolve', '处理争议', 'F', NULL, 10),
    -- 仓库 / 异常 / 充值
    (319, 303, 'ops:warehouse:edit', '仓库作业', 'F', NULL, 10),
    (320, 301, 'ops:exception:handle', '处理异常', 'F', NULL, 10),
    (321, 305, 'ops:recharge:edit', '充值操作', 'F', NULL, 10),
    -- 识别 Demo（挂在商品）
    (322, 51, 'ops:sku:demo', '识别Demo', 'F', NULL, 10),
    -- 订单
    (323, 41, 'ops:order:export', '导出订单', 'F', NULL, 10)
ON CONFLICT (perm_code) DO NOTHING;

-- 新权限授予超级管理员
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN (
    'ops:rbac:role:add', 'ops:rbac:role:edit', 'ops:rbac:role:perm',
    'ops:rbac:menu:add', 'ops:rbac:menu:edit', 'ops:rbac:menu:remove',
    'ops:rbac:assign:role', 'ops:rbac:assign:merchant',
    'ops:dispute:resolve',
    'ops:warehouse:edit', 'ops:exception:handle', 'ops:recharge:edit',
    'ops:sku:demo', 'ops:order:export'
)
ON CONFLICT DO NOTHING;

-- 序列前进，避免后续 CRUD 主键冲突
SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
