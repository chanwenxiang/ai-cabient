-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: payout_account (+2 列：bank_code联行号 / bank_province_city 开户行省市)；site_rent_bill (+3 列：paid_by / paid_voucher_no / paid_remark)
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = 删除 payout_account 上新增的 2 个列与 site_rent_bill 上新增的 3 个列
-- NOTES: 补齐三处「结构性缺失」（2026-10-06 差距审计的零成本小项）：
--   1) payout_account 补 **联行号 bank_code**：银行代付只有户名+账号+开户行三要素时，
--      部分银行无法自动路由，必须补联行号（CNAPS 12 位）。缺它的后果是**打款被渠道退回**，
--      而失败原因往往只写「收款行不匹配」，排查成本高。
--      同时补 bank_province_city（开户行省市）：大额代付常要求省市以匹配清算网点。
--   2) site_rent_bill 补 **审核人 / 付款凭证号 / 付款备注**：
--      原表只有 status + paid_at，运营点「已付」就结束了 —— **谁付的、凭什么付的没留痕**。
--      场地租金是对外付款，属于需要审计追溯的费用，缺凭证号会让「已付」这个状态不可复核。
--      三列全部可空 ⇒ 存量行（含已 PAID 的历史账单）无需回填。
--      ⚠️ 本迁移只加列，**不改状态机**（markPaid 写 paid_by 的逻辑在代码侧 V309 同步补）。
--   并发：仅 ADD COLUMN，不与事务型语句混用；不建索引（无按凭证号检索的场景）。

-- ============ 1. 收款账户补联行号与开户行省市 ============
-- 联行号：CNAPS 联行号 12 位数字，部分银行要求（大额/跨行代付）
ALTER TABLE payout_account
    ADD COLUMN IF NOT EXISTS bank_code VARCHAR(32);

-- 开户行省市：如「广东省深圳市」，部分渠道大额代付要求用于匹配清算网点
ALTER TABLE payout_account
    ADD COLUMN IF NOT EXISTS bank_province_city VARCHAR(128);

-- ============ 2. 场地租金账单补付款留痕字段 ============
-- 付款操作人（运营/财务账号 ID）
ALTER TABLE site_rent_bill
    ADD COLUMN IF NOT EXISTS paid_by BIGINT;

-- 付款凭证号（银行流水号/发票号/线下单号），审计追溯用
ALTER TABLE site_rent_bill
    ADD COLUMN IF NOT EXISTS paid_voucher_no VARCHAR(128);

-- 付款备注（线下支付说明，如「XX银行 2026-09 月租，流水号xxxx」）
ALTER TABLE site_rent_bill
    ADD COLUMN IF NOT EXISTS paid_remark VARCHAR(512);