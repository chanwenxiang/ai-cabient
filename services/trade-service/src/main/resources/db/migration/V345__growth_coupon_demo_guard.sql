-- MIGRATION_KIND: backfill
-- 🔴 生产守卫迁移补遗：V163__growth_marketing_modules 的演示积分兑换券（V344 漏网）。
-- V344 只覆盖了 V99 的三张演示券名；V163 又播了三张「满 N 减 M 券」供其演示兑换目录
-- （points_redeem_item 已被 V344 整表清空，这三张券定义成了孤儿演示数据）。
-- 2026-10-13 生产实证：V344 应用后 coupon_definition 仍余 3 行（id 4-6）。
-- 与 V344 同口径：仅 seed_env='none'（prod/staging）执行；local/dev/uat 不动。

DO $demo_purge_345$
DECLARE
    n bigint;
BEGIN
    IF '${seed_env}' <> 'none' THEN
        RAISE NOTICE 'V345 demo coupon purge skipped: seed_env=% keeps demo fixtures', '${seed_env}';
        RETURN;
    END IF;

    DELETE FROM user_coupon
    WHERE coupon_def_id IN (SELECT coupon_def_id FROM coupon_definition
                            WHERE coupon_name IN ('满 100 减 20 券', '满 50 减 10 券', '满 20 减 5 券'));
    DELETE FROM coupon_definition
    WHERE coupon_name IN ('满 100 减 20 券', '满 50 减 10 券', '满 20 减 5 券');

    SELECT count(*) INTO n FROM coupon_definition;
    IF n > 0 THEN
        RAISE NOTICE 'V345: coupon_definition 仍余 % 行（非 V163 演示名，视为真实配置保留）', n;
    END IF;
    RAISE NOTICE 'V345 demo coupon purge done (seed_env=none)';
END
$demo_purge_345$;
