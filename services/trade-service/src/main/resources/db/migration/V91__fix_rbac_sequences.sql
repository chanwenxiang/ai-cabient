-- 显式插入 role_id / permission_id 后，BIGSERIAL 序列未前进，导致新增角色主键冲突
SELECT setval(
    pg_get_serial_sequence('ops_role', 'role_id'),
    GREATEST((SELECT COALESCE(MAX(role_id), 1) FROM ops_role), 1)
);

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
