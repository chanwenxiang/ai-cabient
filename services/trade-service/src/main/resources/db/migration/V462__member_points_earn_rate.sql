-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low（只加一个可空列；PG11+ ADD COLUMN 无 DEFAULT 不重写表）
-- TABLES: member_points_log
-- NOTES: R20-10（四端审计 2026-11-12）——退款扣回积分必须用**发放时**的积分倍率。
--        旧实现按「退款当时的会员等级倍率」算（MemberService.doClawbackPointsOnRefund），
--        而返积分（earnPoints）用的是**支付当时**的等级倍率。两者在「下单后升级、再退款」时不一致：
--        按 1.0 倍发了 100 分，升到 2.0 倍后全额退款要扣 200 分——多扣的 100 分是用户没拿到的。
--        本列在 EARN 行上记发放倍率，扣回按它算；旧 EARN 行为 NULL（历史数据无法反推：
--        发放分已按 DOWN 取整，用 分/金额 反算会把 1.5 倍算成 1.005 之类），
--        此时回落当前等级倍率（即旧行为），不会比修前更差。
--        本迁移**不回填**：回填只能填近似值，比 NULL 更危险（看起来有值、实际算错）。

ALTER TABLE member_points_log
    ADD COLUMN IF NOT EXISTS earn_rate NUMERIC(5,2);

COMMENT ON COLUMN member_points_log.earn_rate IS
    'EARN 行发放时的积分倍率（1 元 = N 积分）；退款扣回按此倍率，NULL=历史行（回落当前等级倍率）';
