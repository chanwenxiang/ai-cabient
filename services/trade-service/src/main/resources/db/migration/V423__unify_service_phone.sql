-- MIGRATION_KIND: backfill
-- R9 批 3（2026-11-01，MA-027）：客服电话统一到**已通过微信支付客服电话认证**的实号。
--
-- 冲突现场（本轮逐源查证，不是「哪个号好看」）：
--   · `system_config.consumer.service_phone` / `merchant.service_phone` = **400-888-0018**
--     —— V55 播的占位号（迁移头原文就叫「消费者客服电话」，从未按真号订正），
--     帮助中心与两端公开配置都读它（`pages/help/help.vue` → `consumerPublicConfig.servicePhone`）。
--   · `public-legal.json` 的用户协议/隐私政策正文三处 = **4000182992**，
--     且 `docs/figures/payscore-form-fill-pack.md` §55 记着该号「已通过微信支付客服电话认证
--     并展示在账单详情页」⇒ 4000182992 才是对外有效号，400-888-0018 是历史占位。
--   两侧不一致＝用户在帮助中心拨到占位号，而协议里写的号才是能接通的号。
--
-- 处置方向：**改配置侧，不改法律文本**（法律文本里的号是已认证号，动它等于改法定联系方式披露）。
-- 同步改的另外两处默认值（不在本迁移里，属代码侧）：`SystemConfigService.ensureDefaults` 种子、
-- `ops/feature-flags.json` 的 default、`public-legal.json` 的 help.supportPhone。
-- 守卫：只在值仍等于占位号时改 ⇒ 运营若已手工改成别的号，本迁移不覆盖；空库重放同样安全。
-- LOCK_RISK: low
-- TABLES: system_config (~115 rows，命中 2 行)
-- NOTES: 纯 DML；`system_config_history` 不写（非用户操作，无 operator 上下文，审计侧由本迁移头留痕）

UPDATE system_config
   SET config_value = '4000182992'
 WHERE config_key IN ('consumer.service_phone', 'merchant.service_phone')
   AND config_value = '400-888-0018';
