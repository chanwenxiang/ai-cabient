-- V244: demo member price discounts for benefit display
-- =====================================================================
-- v2 修订（C01+H15）：本迁移为 UAT/本地种子数据，现按 Flyway placeholders 环境守卫收窄：
--   仅当 seed-env ∈ (local, dev, uat) 时执行写语句；生产（application-prod.yml 固定 seed-env: none）跳过。
-- 注意 Flyway checksum：已应用过 v1 本迁移的环境升级后需执行 `flyway repair` 对齐校验和。
-- =====================================================================

UPDATE member_level_rule
SET price_discount_pct = 2.00,
    updated_at = NOW()
WHERE level_code = 'SILVER'
  AND COALESCE(is_deleted, false) = false
  AND COALESCE(price_discount_pct, 0) = 0
  AND '${seed_env}' IN ('local','dev','uat');

UPDATE member_level_rule
SET price_discount_pct = 5.00,
    updated_at = NOW()
WHERE level_code = 'GOLD'
  AND COALESCE(is_deleted, false) = false
  AND COALESCE(price_discount_pct, 0) = 0
  AND '${seed_env}' IN ('local','dev','uat');

UPDATE member_level_rule
SET price_discount_pct = 8.00,
    updated_at = NOW()
WHERE level_code = 'PLATINUM'
  AND COALESCE(is_deleted, false) = false
  AND COALESCE(price_discount_pct, 0) = 0
  AND '${seed_env}' IN ('local','dev','uat');
