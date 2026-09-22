-- V284: 通用数据管理权限码 ops:data:manage（从被改写的 V282 按 forward-only 前移）
-- MIGRATION_KIND: backfill
-- LOCK_RISK: low
-- NOTES: 全部幂等；主键由序列分配（不硬编码），可安全重放
-- TABLES: ops_permission (~1), ops_role_permission (~1)
-- =====================================================================
-- 背景（2026-09-22）
--   通用数据管理通道（AdminDataManageController/Service，同一批提交）需要新权限码
--   ops:data:manage。该码最初被**直接写进已应用的 V282**（申请号 714）——而 V282 在 dev 库
--   已于 2026-09-21 12:17 应用，改写它会让 Flyway checksum 漂移
--   （DB 记账 -2104516235 ≠ 改写后 1850328047），下次 validate 即失败、trade-service 起不来。
--
--   按 forward-only 原则：V282 还原为已应用原文，该码**前移到本迁移**，功能不受影响。
--
--   ⚠️ 主键**故意不硬编码 714**：若某环境已按改写版应用过 V282（714 已被占用），
--      硬编码会撞主键（`ON CONFLICT (perm_code)` 挡不住 permission_id 冲突）。
--      交给序列分配 + perm_code 判重，对「全新库」与「已应用改写版 V282」两种环境都安全。
--   ⚠️ 若某环境的 V282 已按改写版应用，需先 `flyway repair`（对齐记账 checksum）再升级。
-- =====================================================================

INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order)
SELECT (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:config:list'),
       'ops:data:manage', '通用数据管理', 'F', NULL, 40
ON CONFLICT (perm_code) DO NOTHING;

-- 超级管理员：补显式授权保持数据面一致（ops:admin 运行时本就短路）
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code = 'ops:data:manage'
ON CONFLICT DO NOTHING;
