-- IMP-026 Playwright UAT: demo PAID order with member discount so admin 差额说明 renders.
-- Idempotent: only patches one recent zero-discount PAID order when no UAT-marked row exists.
-- =====================================================================
-- v2 修订（C01+H15）：本迁移为 UAT/本地种子数据，现按 Flyway placeholders 环境守卫收窄：
--   仅当 seed-env ∈ (local, dev, uat) 时执行写语句；生产（application-prod.yml 固定 seed-env: none）跳过。
-- 注意 Flyway checksum：已应用过 v1 本迁移的环境升级后需执行 `flyway repair` 对齐校验和。
-- =====================================================================

DO $$
DECLARE
    target_order_id VARCHAR(32);
BEGIN
    -- 环境守卫（C01+H15）：非 local/dev/uat 环境直接跳过全部种子写入
    IF '${seed_env}' NOT IN ('local', 'dev', 'uat') THEN
        RETURN;
    END IF;
    SELECT order_id INTO target_order_id
    FROM cabinet_order
    WHERE status = 'PAID'
      AND member_discount_cents = 59
      AND coupon_discount_cents = 0
      AND total_amount_cents = 441
    ORDER BY created_at DESC
    LIMIT 1;

    IF target_order_id IS NOT NULL THEN
        RETURN;
    END IF;

    SELECT order_id INTO target_order_id
    FROM cabinet_order
    WHERE status = 'PAID'
      AND member_discount_cents = 0
      AND coupon_discount_cents = 0
      AND total_amount_cents > 0
    ORDER BY created_at DESC
    LIMIT 1;

    IF target_order_id IS NULL THEN
        RETURN;
    END IF;

    UPDATE cabinet_order
    SET member_discount_cents = 59,
        total_amount_cents = 441
    WHERE order_id = target_order_id;

    UPDATE cabinet_order_line
    SET unit_price_cents = 500,
        line_amount_cents = 500
    WHERE order_id = target_order_id;
END $$;
