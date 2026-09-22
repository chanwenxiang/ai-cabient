-- 语义化按钮权限码收口（F 码）：
-- 1) 通知模块此前 send/edit/delete 全部复用 ops:notify:list，拆分为独立动作码；
-- 2) 实名核验的编辑/删除同理从 ops:phone-verify:list 拆出；
-- 3) 补齐 7 个页面导出按钮缺失的 export 码（此前前端按钮无权限门控）。
-- 后端 @RequiresPermissions 与前端 v-hasPermi/perm 同步于本次变更（见同一提交）。
--
-- MIGRATION_KIND: backfill
-- LOCK_RISK: low

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
VALUES
    -- 消息记录（parent: ops:notify:list C 菜单）
    (700, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:notify:list'),
     'ops:notify:send',           '发送站内信',     'F', NULL, 21),
    (701, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:notify:list'),
     'ops:notify:edit',           '编辑站内信',     'F', NULL, 22),
    (702, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:notify:list'),
     'ops:notify:delete',         '删除站内信',     'F', NULL, 23),
    (703, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:notify:list'),
     'ops:notify:export',         '导出消息记录',   'F', NULL, 24),
    -- 实名核验（parent: ops:phone-verify:list C 菜单）
    (704, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:phone-verify:list'),
     'ops:phone-verify:edit',     '编辑核验记录',   'F', NULL, 21),
    (705, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:phone-verify:list'),
     'ops:phone-verify:delete',   '删除核验记录',   'F', NULL, 22),
    (713, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:phone-verify:list'),
     'ops:phone-verify:export',   '导出核验记录',   'F', NULL, 23),
    -- 导出码补齐（parent: 各自模块 C 菜单）
    (706, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:device-ops:list'),
     'ops:device-ops:export',     '导出设备运维事件', 'F', NULL, 21),
    (707, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:balance-refund:list'),
     'ops:balance-refund:export', '导出余额退款',     'F', NULL, 21),
    (708, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:ad:list'),
     'ops:ad:export',             '导出广告素材/投放', 'F', NULL, 30),
    (709, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:points:list'),
     'ops:points:export',         '导出积分兑换项',   'F', NULL, 21),
    (710, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:ota:list'),
     'ops:ota:export',            '导出OTA版本',      'F', NULL, 21),
    (711, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:org:list'),
     'ops:org:export',            '导出场地合同',     'F', NULL, 21),
    (712, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:task:list'),
     'ops:task:export',           '导出定时任务',     'F', NULL, 21),
    (714, (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:config:list'),
     'ops:data:manage',           '通用数据管理',     'F', NULL, 40)
ON CONFLICT (perm_code) DO NOTHING;

-- 超级管理员：全部新码（ops:admin 运行时本就短路，这里补显式授权保持数据面一致）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN (
    'ops:notify:send', 'ops:notify:edit', 'ops:notify:delete', 'ops:notify:export',
    'ops:phone-verify:edit', 'ops:phone-verify:delete', 'ops:phone-verify:export',
    'ops:device-ops:export', 'ops:balance-refund:export', 'ops:ad:export',
    'ops:points:export', 'ops:ota:export', 'ops:org:export', 'ops:task:export',
    'ops:data:manage'
)
ON CONFLICT DO NOTHING;

-- 运营人员：仅导出（与 V106 口径一致：不含写操作，避免业务角色误删/误发）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission
WHERE perm_code IN (
    'ops:notify:export', 'ops:phone-verify:export'
) ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
