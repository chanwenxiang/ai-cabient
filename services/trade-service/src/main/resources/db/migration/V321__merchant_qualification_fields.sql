-- V321: 提现资质门禁所需的商户主体资质字段
--
-- 背景（2026-10-07 核实 F2「无提现实名/KYB 校验」）
-- ------------------------------------------------------------------
-- 库表 `merchant` 里**本来就有** `legal_person` / `business_license_url` 两列，
-- 但 🔴 `Merchant` 域类**没有映射它们** ⇒ 服务层读不到，写入路径也没有。
-- 逐行读 `MerchantWithdrawService.merchantApply` 确认：
-- 提现只校验「商户存在 + 钱包锁 + 限额 + 收款账户」，**不校验任何主体资质**。
--
-- 而 `user.verified`（实名结果）全仓只在 `UserValidationService:81`
-- 用作**开门门禁** —— 资金出账口完全不看它。
--
-- ⇒ 本迁移只做一件事：**把已存在的两列接到域类上**，让门禁能读到。
-- 🔴 刻意**不新建**「资质审核状态」列 —— 那需要真实的审核流程与运营页面，
--   在没有审核能力前加一个 status 只会让人以为「已审核」。
--
-- 🔴 刻意**不加** `user_realname_auth` 的写入：实名是**委托第三方**校验的
--   （IdentityVerifyClient 只传姓名 + 证件后 4 位），本地从不存完整证件号。
--   那是**有意的隐私设计**；为做风控而把完整证件号存进自己库反而**扩大泄露面**。

-- ① 域类缺的两列：补到 merchant 表（IF NOT EXISTS —— 可能已在历史脚本里加过）
ALTER TABLE merchant
    ADD COLUMN IF NOT EXISTS legal_person VARCHAR(64);

ALTER TABLE merchant
    ADD COLUMN IF NOT EXISTS business_license_url VARCHAR(255);

COMMENT ON COLUMN merchant.legal_person IS
    'V321 法人姓名。提现门禁必填之一（有则合法、但内容真伪由人工审核负责）。';
COMMENT ON COLUMN merchant.business_license_url IS
    'V321 营业执照图片/文件地址。提现门禁必填之一。⚠️ 存的是地址不是文件本体（文件走 FileAttachment）。';

-- ② 查询索引：门禁每次提现都会按 merchant_id 查（主键已有，足够）
--    本次**不新增索引** —— 门禁走主键，加索引是浪费。

-- 迁移后必须验的
-- 1. SELECT column_name FROM information_schema.columns
--    WHERE table_name='merchant' AND column_name IN ('legal_person','business_license_url');
--    ⇒ 2 行都应存在
-- 2. 负向：不加 CHECK/NOT NULL —— 存量商户大量为空是**正常状态**，
--    门禁在应用侧拦，DB 侧强制会让存量商户无法写入任何更新。
