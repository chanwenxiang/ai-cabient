-- 补齐运营后台导入/导出按钮权限（F），挂到对应菜单（C）
-- 已有：ops:order:export, ops:coupon:export, ops:operlog:export

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
VALUES
    (410, 21,  'ops:device:export',          '导出设备',     'F', NULL, 20),
    (411, 31,  'ops:session:export',         '导出会话',     'F', NULL, 20),
    (412, 51,  'ops:sku:export',             '导出商品',     'F', NULL, 20),
    (413, 51,  'ops:sku:import',             '导入商品',     'F', NULL, 21),
    (414, 70,  'ops:dispute:export',         '导出争议',     'F', NULL, 20),
    (415, 301, 'ops:exception:export',       '导出异常',     'F', NULL, 20),
    (416, 261, 'ops:announcement:export',    '导出公告',     'F', NULL, 20),
    (417, 261, 'ops:announcement:import',    '导入公告',     'F', NULL, 21),
    (418, 231, 'ops:dict:export',            '导出字典',     'F', NULL, 20),
    (419, 231, 'ops:dict:import',            '导入字典',     'F', NULL, 21),
    (420, 307, 'ops:config:export',          '导出参数',     'F', NULL, 20),
    (421, 307, 'ops:config:import',          '导入参数',     'F', NULL, 21),
    (422, 141, 'ops:audit:export',           '导出审计',     'F', NULL, 20),
    (423, 12,  'ops:report:export',          '导出报表',     'F', NULL, 20),
    (424, 160, 'ops:upload:export',          '导出上传队列', 'F', NULL, 20),
    (425, 305, 'ops:recharge:export',        '导出充值',     'F', NULL, 20),
    (426, 303, 'ops:warehouse:export',       '导出仓库',     'F', NULL, 20),
    (427, 303, 'ops:warehouse:import',       '导入仓库',     'F', NULL, 21),
    (428, 111, 'ops:replenishment:export',   '导出补货',     'F', NULL, 20),
    (429, 91,  'ops:risk:export',            '导出风控',     'F', NULL, 20),
    (430, 61,  'ops:user:export',            '导出用户',     'F', NULL, 20),
    (431, 171, 'ops:merchant:export',        '导出商户',     'F', NULL, 20),
    (432, 101, 'ops:reconciliation:export',  '导出对账',     'F', NULL, 20),
    (433, 270, 'ops:feedback:export',        '导出反馈',     'F', NULL, 20),
    (434, 103, 'ops:finance:export',         '导出财务',     'F', NULL, 20),
    (435, 51,  'ops:vision:export',          '导出识别映射', 'F', NULL, 22),
    (436, 241, 'ops:promotion:export',       '导出活动',     'F', NULL, 20),
    (437, 241, 'ops:promotion:import',       '导入活动',     'F', NULL, 21),
    (438, 245, 'ops:coupon:import',          '导入优惠券',   'F', NULL, 21),
    (439, 131, 'ops:rbac:role:export',       '导出角色',     'F', NULL, 20),
    (440, 131, 'ops:rbac:role:import',       '导入角色',     'F', NULL, 21),
    (441, 132, 'ops:rbac:assign:export',     '导出运营账号', 'F', NULL, 20),
    (442, 132, 'ops:rbac:assign:import',     '导入运营账号', 'F', NULL, 21),
    (443, 133, 'ops:rbac:menu:export',       '导出菜单',     'F', NULL, 20)
ON CONFLICT (perm_code) DO NOTHING;

-- 超级管理员：全部新 F 码
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN (
    'ops:device:export', 'ops:session:export',
    'ops:sku:export', 'ops:sku:import',
    'ops:dispute:export', 'ops:exception:export',
    'ops:announcement:export', 'ops:announcement:import',
    'ops:dict:export', 'ops:dict:import',
    'ops:config:export', 'ops:config:import',
    'ops:audit:export', 'ops:report:export', 'ops:upload:export',
    'ops:recharge:export',
    'ops:warehouse:export', 'ops:warehouse:import',
    'ops:replenishment:export', 'ops:risk:export', 'ops:user:export',
    'ops:merchant:export', 'ops:reconciliation:export',
    'ops:feedback:export', 'ops:finance:export', 'ops:vision:export',
    'ops:promotion:export', 'ops:promotion:import', 'ops:coupon:import',
    'ops:rbac:role:export', 'ops:rbac:role:import',
    'ops:rbac:assign:export', 'ops:rbac:assign:import',
    'ops:rbac:menu:export'
)
ON CONFLICT DO NOTHING;

-- 运营人员：仅导出（不含导入，避免只读业务角色灌库）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission
WHERE perm_code IN (
    'ops:device:export', 'ops:session:export',
    'ops:sku:export',
    'ops:dispute:export', 'ops:exception:export',
    'ops:announcement:export',
    'ops:dict:export',
    'ops:audit:export', 'ops:report:export', 'ops:upload:export',
    'ops:recharge:export',
    'ops:warehouse:export',
    'ops:replenishment:export', 'ops:risk:export', 'ops:user:export',
    'ops:merchant:export', 'ops:reconciliation:export',
    'ops:feedback:export', 'ops:finance:export', 'ops:vision:export',
    'ops:promotion:export', 'ops:coupon:export', 'ops:order:export'
)
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
