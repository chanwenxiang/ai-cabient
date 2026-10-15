-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- COMPETITOR_REF: CB-022
-- NOTES: V366（审计 A1/P0-3）：将邑设备 token 签发激活制。SN 是出厂可枚举标识
--（设备贴纸/固件可读），仅凭「已登记+BOUND」即发 token 无法防仿冒——仿冒 SN
-- 可伪造识别结果驱动扣款。新增 auth_enabled（默认 FALSE）：运营台逐台激活后才
-- 可领 token（预授权模式，与 CB-022「金额平台自持」同源）；关闭时同步吊销
-- token_version（网关侧实现，见 DeviceTokenController）。
-- 存量行默认 FALSE：上线后需运营对在用将邑设备逐台激活（一次性运维动作）。
-- 竞品：docs/COMPETITOR_BENCHMARK.md CB-022 / 审计 docs/CODE_AUDIT_REPORT_2026-10-05_FULL.md P0-3。

ALTER TABLE jiangyi_device
    ADD COLUMN IF NOT EXISTS auth_enabled BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN jiangyi_device.auth_enabled IS 'token 签发激活开关（审计 A1/P0-3）：FALSE=拒发 token；关闭时须同步 bump token_version 吊销存量 token';
