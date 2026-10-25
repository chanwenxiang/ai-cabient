-- COMPETITOR_REF: CB-066
-- 线长「身份核验」时间戳：运营侧经二要素 vendor 核验（或 C 端实名通过）后落值，
-- 作为「收款账户持有人 = 实名本人」一致性守卫的前置（PayoutAccountService.create 对
-- LINE_MANAGER 主体要求非空）。遵守 V321 隐私拍板：不落证件号，只落核验时间。
ALTER TABLE line_manager
    ADD COLUMN identity_verified_at timestamptz;
