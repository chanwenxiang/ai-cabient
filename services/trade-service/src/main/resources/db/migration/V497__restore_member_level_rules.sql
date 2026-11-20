-- V497: 补回会员等级规则（V344 清演示数据时把功能必需的配置表一起清空了）
-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- TABLES: member_level_rule（应恒有 4 行；生产 2026-11-20 实测 0 行）
-- LOCK_RISK: low（最多 4 行 INSERT … ON CONFLICT DO NOTHING，已在表内则完全不动）
-- ROLLBACK: 不回滚 —— 本迁移只补回产品必需的默认配置；真要回退由运营台改 status，不要删行
-- NOTES: 真机会员中心「等级显示 NORMAL／已达最高等级／等级说明整段空白」的根因。
--   V79/V99 曾种过这 4 行等级规则，V283 又按「等级规则是**功能必需配置**而非 demo 数据」
--   的口径幂等补回；但 V344（上线前清演示数据）第 5 段把 `member_level_rule` 整表 DELETE，
--   而它排在这两个迁移之后 ⇒ 生产落到 0 行。后果是 `ConsumerMemberFacade.profile` 里
--   `current == null`：等级名回退成编码（前端显示英文 NORMAL）、`next == null`（显示
--   「已达最高等级」）、`levels` 空数组（等级说明整段空白）、`calculateMemberLevel` 恒落
--   NORMAL（会员价折扣与升级体系整体失效）。
--   口径与 V79/V99 原始种子逐字一致（含 min_points / points_rate）；`min_recharge` 按
--   V283 的「消费门槛一半」口径回填。已存在的行（运营调过名/门槛/倍率）一律不动，
--   只用 WHERE ... IS NULL 填空值，可安全重放。
--   为什么不放 db/seed/R__：可重复种子在 Flyway 里**先于** V344 执行，checksum 没变就
--   不会再跑，补不回被 V344 删掉的行；这里需要的是「V344 之后单次生效」的修复。
-- ⚠️ 后续任何「清演示数据」的迁移不得再 DELETE 本表（门禁 check:seed-guard-scope 钉住）。

INSERT INTO member_level_rule
    (level_code, level_name, min_spent, max_spent, min_points, points_rate, sortorder, status)
VALUES
    ('NORMAL',   '普通会员', 0,     1000,  0,    1.00, 1, 'ACTIVE'),
    ('SILVER',   '银卡会员', 1000,  5000,  100,  1.20, 2, 'ACTIVE'),
    ('GOLD',     '金卡会员', 5000,  10000, 500,  1.50, 3, 'ACTIVE'),
    ('PLATINUM', '白金会员', 10000, NULL,   1000, 2.00, 4, 'ACTIVE')
ON CONFLICT (level_code) DO NOTHING;

-- 储值门槛按 V283 口径回填（消费门槛的一半），只填 NULL，运营调过的值不动。
UPDATE member_level_rule SET min_recharge = 0    WHERE level_code = 'NORMAL'   AND min_recharge IS NULL;
UPDATE member_level_rule SET min_recharge = 500  WHERE level_code = 'SILVER'   AND min_recharge IS NULL;
UPDATE member_level_rule SET min_recharge = 2000 WHERE level_code = 'GOLD'     AND min_recharge IS NULL;
UPDATE member_level_rule SET min_recharge = 5000 WHERE level_code = 'PLATINUM' AND min_recharge IS NULL;
