-- V244: demo member price discounts for benefit display

UPDATE member_level_rule
SET price_discount_pct = 2.00,
    updated_at = NOW()
WHERE level_code = 'SILVER'
  AND COALESCE(is_deleted, false) = false
  AND COALESCE(price_discount_pct, 0) = 0;

UPDATE member_level_rule
SET price_discount_pct = 5.00,
    updated_at = NOW()
WHERE level_code = 'GOLD'
  AND COALESCE(is_deleted, false) = false
  AND COALESCE(price_discount_pct, 0) = 0;

UPDATE member_level_rule
SET price_discount_pct = 8.00,
    updated_at = NOW()
WHERE level_code = 'PLATINUM'
  AND COALESCE(is_deleted, false) = false
  AND COALESCE(price_discount_pct, 0) = 0;
