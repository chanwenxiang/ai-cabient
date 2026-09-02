-- IMP-026 Playwright UAT: demo PAID order with member discount so admin 差额说明 renders.
-- Idempotent: only patches one recent zero-discount PAID order when no UAT-marked row exists.

DO $$
DECLARE
    target_order_id VARCHAR(32);
BEGIN
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
