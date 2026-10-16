-- MIGRATION_KIND: schema
-- DC-29 执行清单第 5 项：清掉 V357 留在 `line_manager` 上的两列**死列**。
--
-- 为什么是死列（2026-10-16 全仓取证，非推断）：
--   · `LineManager.java` domain 里没有对应字段（只有 commissionRateBps / commissionFixedCents）；
--   · 全仓 grep `commission_basis` / `commission_per_unit_cents` 的命中**全部属于别的表**：
--     V358 的 `line_route_member`（补货员侧双基数，真正在用的实现，`LineRouteMember.java` 有字段、
--     admin `LineRoutesView.vue` 有编辑面）、V361 的 `staff_commission_daily`（发佣时记录的模式）；
--   · 没有任何 raw SQL / mapper XML / 脚本按这两个列名读 `line_manager`。
-- 历史成因写在 V357 自己的注释里：双基数口径 2026-10-14 二次修正后落到 V358，V357 那两列「保留兼容」，
--   但兼容面为零 ⇒ 按 V346 死表先例判清理。
-- 铁律 50：V357 已应用不可改写（checksum），只能在追加迁移里 DROP。
--
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: line_manager (~十量级，非热表；不在 check-migration-safety HOT_TABLES 名单)
-- NOTES: DROP COLUMN 只取 ACCESS EXCLUSIVE 元数据锁、不重写表；表体量小、无并发写该列的代码路径。
--        不带 IF EXISTS——列由 V357 无条件添加，缺失即说明库状态异常，宁可让迁移当场红。

ALTER TABLE line_manager DROP COLUMN commission_basis;
ALTER TABLE line_manager DROP COLUMN commission_per_unit_cents;
