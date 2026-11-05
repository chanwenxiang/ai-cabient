-- MIGRATION_KIND: backfill
-- 批 3b 后续（2026-11-05）：退役 `merchant:slots:view`（225 号，F 按钮位）。
--
-- 判据（四路现扫，排除迁移自身；V427 同规）：
--   `grep -rn "merchant:slots:view" services/*/src clients/*/src packages/*/src scripts`
--     ⇒ 命中 5 个文件**全部**是 db/migration 里的历史播种（V49/V50/V121/V140/V265），
--     非迁移命中 0 条；注解与编程式 `requirePermission` 也没有它。
--   成因：该码仅有的两个消费者已随前五刀删除——
--     GET /api/v2/merchant/devices/{deviceId}/slots（第二刀）、
--     GET /api/v2/ops/admin/devices/{deviceId}/slots（第一刀）。
--   🔴 兄弟码 `merchant:slots:edit` **仍在用**（`MerchantPortalController` 的
--     /stocktake-door、/door-stocktake、盘点凭证上传三处 @RequiresPermissions）⇒
--     父行 202 有活子节点，父行与 edit 都不动，勿顺手对齐。
-- 处置沿用 V386/V421/V427 口径：**RETIRED 不删行、不删授权**。
--   生效点：`mapper/OpsPermissionMapper.xml` 的 `selectPermCodesByUserId`（12-19 行，
--   ACTIVE 判据在第 17 行）与 `findUserIdsByPermCode`（21-34 行，ACTIVE 判据在第 29 行）
--   ⇒ 状态一改，该码即刻不再进任何人的权限集与角色树；菜单管理 includeInactive 仍可留痕。
-- 幂等：`AND status <> 'RETIRED'`；不改任何历史迁移；无 DDL、无外键变更。
-- ROLLBACK: UPDATE ops_permission SET status = 'ACTIVE' WHERE perm_code = 'merchant:slots:view';
-- LOCK_RISK: low
-- TABLES: ops_permission（dev 库实测：225 号 F 行 status=ACTIVE，role 3/6/7/10/11 共 5 条授权）
-- EVIDENCE: 本迁移已在 fresh 库（Testcontainers，V1→V442 严格升序）实跑，日志见
--   `Migrating schema "public" to version "442 - retire merchant slots view perm"`，
--   同轮 `PermClosureSqlIT` 3/3 绿 ⇒ 权限闭环在退役后仍成立。
-- NOTES: 纯 DML 改状态；角色侧既有授权行保留（读侧按 ACTIVE 过滤即失效）
UPDATE ops_permission
   SET status = 'RETIRED'
 WHERE perm_code = 'merchant:slots:view'
   AND status <> 'RETIRED';
