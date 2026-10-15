-- MIGRATION_KIND: backfill（DDL 加列；存量行落默认值=老系统口径，登记可改）
-- COMPETITOR_REF: CB-044
-- B5 拍板（2026-10-15 选项 1）：押金撤机清算三件套参数化——费用不写死（铁律同 V360）。
-- 老系统口径（GuaranteeCashService.dealGuaranteeCashAndInstallationCharge + GuaranteeCashType）：
--   撤机未退完 → 退剩余押金 − 安装费 100 元；已退完未满 5 年 → 向商户余额倒扣安装费 100 元；
--   满 5 年撤机 → 发期满奖励 200 元（新系统奖励额走 expire_reward_cents，登记可填）。
-- install_fee_deduct_cents 默认 10000 分=100 元；install_fee_clawback_enabled 默认开；contract_years 默认 5。
ALTER TABLE device_rental_deposit
    ADD COLUMN IF NOT EXISTS install_fee_deduct_cents BIGINT NOT NULL DEFAULT 10000,
    ADD COLUMN IF NOT EXISTS install_fee_clawback_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS contract_years INT NOT NULL DEFAULT 5;
