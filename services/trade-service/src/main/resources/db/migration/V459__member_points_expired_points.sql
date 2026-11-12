-- MIGRATION_KIND: backfill
-- （原声明 schema 但含 UPDATE 回填，被 check-flyway-seed-separation 判为自相矛盾；
--   本迁移未在任何环境应用过（flyway history 无 459），改声明不影响 checksum。）
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low（只加列 + 一条按主键以外的全表 UPDATE；dev 表千行级，ADD COLUMN 带
--             DEFAULT 在 PG11+ 不重写表）
-- TABLES: member_points_log
-- NOTES: R20-5（四端审计 2026-11-12）——积分过期「部分结转」需要记住每条 EARN 已被过期多少。
--        旧实现只剩一个 expired_at 布尔语义：`actualExpire = min(available, Σ)` 时，
--        整条 pts > remaining 的行被跳过且不写 expired_at，下一轮同一行又被整额重算：
--        EARN=100、余额 10 时本轮只扣 10 不标记，用户新赚 100 后再扣 100，累计扣 110 > 100。
--        新增 expired_points 记「该行已过期掉的积分数」，与 expired_at 分工：
--        expired_at 只在整行耗尽时落，部分结转的行走下一轮继续算 min(剩余, 预算)。
--        回填：历史已置 expired_at 的 EARN 行视为整额过期（旧实现只有整条过期才会写该列）。

ALTER TABLE member_points_log
    ADD COLUMN IF NOT EXISTS expired_points INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN member_points_log.expired_points IS
    '该 EARN 行已过期掉的积分数（部分结转累计值；expired_at 仅在整行耗尽时置位）';

UPDATE member_points_log
   SET expired_points = points
 WHERE expired_at IS NOT NULL
   AND points > 0
   AND expired_points = 0;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_member_points_expired_nonneg') THEN
        ALTER TABLE member_points_log
            ADD CONSTRAINT chk_member_points_expired_nonneg CHECK (expired_points >= 0);
    END IF;
END $$;
