-- V397：登记 ops:ota:publish（P2 真缺口）与 ops:audit:recent（P3 整洁度）
--
-- 成因：ops:ota:publish 后端（OpsOtaController/OpsCommercialFacade/OpsOtaAdminService）
-- 与前端 OtaView.vue 都在用，但 ops_permission 无此行——非超管角色在角色权限勾选界面
-- 看不到该项，永远无法发布 OTA（超管靠 ops:admin 短路不受影响）。
-- ops:audit:recent 同理未登记；后端两处引用均为 OR 语义（ops:audit:list 兜底，不影响
-- 可达性），此处补登使权限项可显式授予。
-- parent 81 = OTA 固件页（ops:ota:list），parent 141 = 审计日志页（ops:audit:list），
-- 与 V93 对齐后的形态一致。perm_code 唯一约束 + ON CONFLICT：重放安全。
--
-- ⚠️ 序列前置校准（CI 实测 23505 撞 ops_permission_pkey）：历史迁移用显式 permission_id
-- 种子（最大 722）不推序列，全新库上 nextval 会给出已存在 id。先 setval 到当前 max 再插入。
-- 惯法同 V106/V108/V110。

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);

INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
VALUES (81, 'ops:ota:publish', '发布OTA版本', 'F', '', 22, 'ACTIVE')
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
VALUES (141, 'ops:audit:recent', '近期审计', 'F', '', 71, 'ACTIVE')
ON CONFLICT (perm_code) DO NOTHING;
