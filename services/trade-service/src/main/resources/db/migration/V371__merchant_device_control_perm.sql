-- MIGRATION_KIND: backfill（RBAC 权限种子数据；schema 声明与 INSERT 矛盾，2026-10-16 门禁整改）
-- MIGRATION_REVIEWED: yes
-- COMPETITOR_REF: CB-050
-- NOTES: 商户端设备控制权限化下放（CB-050，2026-10-16 用户拍板）：新权限点
--       merchant:device:control，指令白名单 LOCK/UNLOCK/REBOOT（OPEN_DOOR 不下放=资金敏感）。
--       默认授商户管理员角色（role_id=6），子账号由商户在角色管理自行分配。
-- TABLE: ops_permission / ops_role_permission（既有 RBAC 表）
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (720, 200, 'merchant:device:control', '设备远程控制', 'C', '/merchant/devices', 14)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code = 'merchant:device:control'
ON CONFLICT DO NOTHING;
