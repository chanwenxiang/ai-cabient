-- 若依风格：超级权限标记 + 补齐前台菜单缺失的 C 级权限（permission_id 避开已占用段）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
VALUES
    (2,   1, 'ops:admin',              '超级管理员', 'F', NULL,                      0),
    (12,  1, 'ops:report:device',      '设备报表',   'C', '/admin/reports',          0),
    (300, 1, 'ops:exception',          '异常中心',   'M', NULL,                      18),
    (301,300,'ops:exception:list',     '异常列表',   'C', '/admin/exceptions',       1),
    (302, 1, 'ops:warehouse',          '仓库管理',   'M', NULL,                      18),
    (303,302,'ops:warehouse:list',     '仓库列表',   'C', '/admin/warehouse',        1),
    (304, 1, 'ops:recharge',           '充值管理',   'M', NULL,                      18),
    (305,304,'ops:recharge:list',      '充值列表',   'C', '/admin/recharges',        1),
    (306, 1, 'ops:config',             '参数配置',   'M', NULL,                      22),
    (307,306,'ops:config:list',        '参数查看',   'C', '/admin/system-configs',   1),
    (308,306,'ops:config:edit',        '参数编辑',   'F', NULL,                      2)
ON CONFLICT (perm_code) DO NOTHING;

-- 新权限授予超级管理员
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN (
    'ops:admin',
    'ops:report:device',
    'ops:exception', 'ops:exception:list',
    'ops:warehouse', 'ops:warehouse:list',
    'ops:recharge', 'ops:recharge:list',
    'ops:config', 'ops:config:list', 'ops:config:edit'
)
ON CONFLICT DO NOTHING;

-- 运营人员可读异常/报表/仓库/充值/参数
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission
WHERE perm_code IN (
    'ops:report:device',
    'ops:exception', 'ops:exception:list',
    'ops:warehouse', 'ops:warehouse:list',
    'ops:recharge', 'ops:recharge:list',
    'ops:config', 'ops:config:list'
)
ON CONFLICT DO NOTHING;
