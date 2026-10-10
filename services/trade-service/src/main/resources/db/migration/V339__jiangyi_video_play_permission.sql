-- COMPETITOR_REF: CB-029
-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- NOTES: 新增按钮级权限 ops:device:video（父菜单 21「设备管理」，排在 ops:device:ref 之后）。独立权限点而非复用 ops:device:list 的原因见 docs/COMPETITOR_BENCHMARK.md CB-029：行业团标《智能柜用户交易视频隐私保护规范》要求交易视频单独约束，查看交易视频须与「看设备列表」分开授权，默认只授超级管理员(1)与运营人员(2)，其余角色需显式授予。纯新增行，无数据回填、无表结构变更，重放安全。
-- TABLES: ops_permission (~1 row), ops_role_permission (~2 rows)
-- 将邑视频复核读路径（CB-029）：权限种子。

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
VALUES (715, 21, 'ops:device:video', '查看订单视频', 'F', NULL, 11)
ON CONFLICT (perm_code) DO NOTHING;

-- 超级管理员：全量
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission WHERE perm_code = 'ops:device:video'
ON CONFLICT DO NOTHING;

-- 运营人员：设备/订单/会话本域能力，含视频复核
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission WHERE perm_code = 'ops:device:video'
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
