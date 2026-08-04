-- RBAC（借鉴 RuoYi：角色 / 菜单权限 / 用户角色 / 角色权限）

CREATE TABLE IF NOT EXISTS ops_role (
    role_id     BIGSERIAL    PRIMARY KEY,
    role_key    VARCHAR(64)  NOT NULL UNIQUE,
    role_name   VARCHAR(64)  NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    remark      VARCHAR(256),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ops_permission (
    permission_id BIGSERIAL    PRIMARY KEY,
    parent_id     BIGINT       NOT NULL DEFAULT 0,
    perm_code     VARCHAR(128) NOT NULL UNIQUE,
    perm_name     VARCHAR(64)  NOT NULL,
    perm_type     VARCHAR(8)   NOT NULL DEFAULT 'M',
    path          VARCHAR(128),
    sort_order    INT          NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE'
);

CREATE TABLE IF NOT EXISTS ops_user_role (
    user_id  BIGINT NOT NULL,
    role_id  BIGINT NOT NULL REFERENCES ops_role(role_id) ON DELETE CASCADE,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS ops_role_permission (
    role_id        BIGINT NOT NULL REFERENCES ops_role(role_id) ON DELETE CASCADE,
    permission_id  BIGINT NOT NULL REFERENCES ops_permission(permission_id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- 角色
INSERT INTO ops_role (role_id, role_key, role_name, remark) VALUES
    (1, 'admin',       '超级管理员', '全部权限'),
    (2, 'operator',    '运营人员',   '设备/订单/会话'),
    (3, 'replenisher', '补货员',     '补货与库存'),
    (4, 'finance',     '财务',       '对账'),
    (5, 'viewer',      '只读',       '仅查看')
ON CONFLICT (role_key) DO NOTHING;

-- 权限树（M=目录 C=菜单 F=按钮/API）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (1,   0, 'ops',                    '运营管理',       'M', NULL,                          1),
    (10,  1, 'ops:dashboard:view',     '概览',           'C', '/admin/dashboard',            1),
    (20,  1, 'ops:device',             '设备管理',       'M', NULL,                          2),
    (21, 20, 'ops:device:list',       '设备列表',       'C', '/admin/devices',              1),
    (22, 20, 'ops:device:edit',       '设备编辑',       'F', NULL,                          2),
    (30,  1, 'ops:session',            '会话管理',       'M', NULL,                          3),
    (31, 30, 'ops:session:list',      '会话列表',       'C', '/admin/sessions',             1),
    (32, 30, 'ops:session:cancel',    '取消会话',       'F', NULL,                          2),
    (40,  1, 'ops:order',              '订单管理',       'M', NULL,                          4),
    (41, 40, 'ops:order:list',        '订单列表',       'C', '/admin/orders',               1),
    (50,  1, 'ops:sku',                '商品管理',       'M', NULL,                          5),
    (51, 50, 'ops:sku:list',          '商品列表',       'C', '/admin/skus',                 1),
    (52, 50, 'ops:sku:edit',          '商品编辑',       'F', NULL,                          2),
    (60,  1, 'ops:user',               '用户管理',       'M', NULL,                          6),
    (61, 60, 'ops:user:list',         '用户列表',       'C', '/admin/users',                1),
    (62, 60, 'ops:user:balance',      '余额调整',       'F', NULL,                          2),
    (70,  1, 'ops:dispute',            '争议审核',       'C', '/admin/disputes',             7),
    (80,  1, 'ops:ota',                '设备 OTA',       'M', NULL,                          8),
    (81, 80, 'ops:ota:list',          'OTA 版本',       'C', '/admin/ota',                  1),
    (82, 80, 'ops:ota:publish',       '发布版本',       'F', NULL,                          2),
    (90,  1, 'ops:risk',               '风控',           'M', NULL,                          9),
    (91, 90, 'ops:risk:list',         '风控事件',       'C', '/admin/risk',                 1),
    (92, 90, 'ops:risk:blacklist',    '黑名单管理',     'F', NULL,                          2),
    (100, 1, 'ops:reconciliation',    '对账',           'M', NULL,                          10),
    (101,100,'ops:reconciliation:list','对账记录',     'C', '/admin/reconciliation',       1),
    (102,100,'ops:reconciliation:run', '执行对账',      'F', NULL,                          2),
    (110, 1, 'ops:replenishment',     '补货',           'M', NULL,                          11),
    (111,110,'ops:replenishment:list','补货任务',       'C', '/admin/replenishment',        1),
    (112,110,'ops:replenishment:edit','补货编辑',       'F', NULL,                          2),
    (120, 1, 'ops:sla',                'SLA 监控',       'C', '/admin/sla',                  12),
    (130, 1, 'ops:rbac',               '权限管理',       'M', NULL,                          13),
    (131,130,'ops:rbac:role',         '角色管理',       'C', '/admin/rbac',                 1),
    (132,130,'ops:rbac:assign',       '用户授权',       'F', NULL,                          2)
ON CONFLICT (perm_code) DO NOTHING;

-- admin 全权限
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
ON CONFLICT DO NOTHING;

-- operator：日常运营
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission
WHERE perm_code IN (
    'ops', 'ops:dashboard:view',
    'ops:device', 'ops:device:list', 'ops:device:edit',
    'ops:session', 'ops:session:list', 'ops:session:cancel',
    'ops:order', 'ops:order:list',
    'ops:sku', 'ops:sku:list', 'ops:sku:edit',
    'ops:user', 'ops:user:list',
    'ops:dispute', 'ops:sla'
)
ON CONFLICT DO NOTHING;

-- replenisher
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 3, permission_id FROM ops_permission
WHERE perm_code LIKE 'ops:replenishment%'
ON CONFLICT DO NOTHING;

-- finance
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 4, permission_id FROM ops_permission
WHERE perm_code LIKE 'ops:reconciliation%'
ON CONFLICT DO NOTHING;

-- viewer 只读
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 5, permission_id FROM ops_permission
WHERE perm_code IN (
    'ops', 'ops:dashboard:view',
    'ops:device:list', 'ops:session:list', 'ops:order:list',
    'ops:sku:list', 'ops:user:list', 'ops:sla'
)
ON CONFLICT DO NOTHING;

-- 本地运营测试账号 → admin
INSERT INTO ops_user_role (user_id, role_id) VALUES (100000001, 1)
ON CONFLICT DO NOTHING;
