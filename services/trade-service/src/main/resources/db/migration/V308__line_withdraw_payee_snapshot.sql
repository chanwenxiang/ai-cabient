-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: line_withdraw_request (+8 列，与 V307 的 merchant_withdraw_request 对齐)；payout_account (仅数据)
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = 删除 line_withdraw_request 上新增的 8 个列
-- NOTES: 线长侧提现收款方模型（V308）。V307 只给商户侧补了收款方快照与幂等键，
--   线长侧仍停在 V133 的裸表结构 —— 提现单不知道自己打给谁、也没有幂等键。
--   1) 补 8 列，与 merchant_withdraw_request 完全对齐：
--      payout_account_id / payee_account_type / payee_account_name /
--      payee_account_no_mask / payee_bank_name / payee_tax_no /
--      idem_key / channel_order_no（+ channel_batch_no 视需要，本期一并补齐保持两侧对称）。
--      payee_* 为**申请时快照，历史不可变**（金融口径：下单即锁定收款人）。
--   2) idem_key 唯一索引：防「渠道已出款但本地回执丢失」导致重试重复出款。
--      与商户侧同口径。
--   3) 种子：给有余额的线长建一个默认**对私微信**收款账户。
--      线长的钱来自佣金分成，主体是**自然人** ⇒ 对私，不能像商户那样默认对公。
--      🔴 account_no 是明文占位符（PayoutFieldCipher 对 DEMO-ENCRYPTED-PLACEHOLDER
--      前缀原样保留，不产生伪密文）⇒ 便于识别「这条不是真账号」。
--      生产必须走 admin 接口写入真实 openid 密文。
--   新列全部可空 ⇒ 存量行（已 PAID 的历史提现）无需回填即可兼容。
--   并发：仅 ADD COLUMN + CREATE INDEX + INSERT，不与事务型语句混用。

-- ============ 1. 提现单补收款方快照 + 幂等键 ============
ALTER TABLE line_withdraw_request
    ADD COLUMN IF NOT EXISTS payout_account_id BIGINT;

-- 以下 5 列为**申请时快照**，历史不可变（账户后续被改/停用不影响已成立的提现单）
ALTER TABLE line_withdraw_request
    ADD COLUMN IF NOT EXISTS payee_account_type VARCHAR(32);
ALTER TABLE line_withdraw_request
    ADD COLUMN IF NOT EXISTS payee_account_name VARCHAR(128);
ALTER TABLE line_withdraw_request
    ADD COLUMN IF NOT EXISTS payee_account_no_mask VARCHAR(64);
ALTER TABLE line_withdraw_request
    ADD COLUMN IF NOT EXISTS payee_bank_name VARCHAR(128);
ALTER TABLE line_withdraw_request
    ADD COLUMN IF NOT EXISTS payee_tax_no VARCHAR(64);

-- 打款幂等键：渠道已出款但本地回执丢失时，重试复用同键 ⇒ 渠道侧不会重复出款
ALTER TABLE line_withdraw_request
    ADD COLUMN IF NOT EXISTS idem_key VARCHAR(96);
-- 渠道单号/批次号：供对账与人工核单
ALTER TABLE line_withdraw_request
    ADD COLUMN IF NOT EXISTS channel_order_no VARCHAR(128);
ALTER TABLE line_withdraw_request
    ADD COLUMN IF NOT EXISTS channel_batch_no VARCHAR(128);

COMMENT ON COLUMN line_withdraw_request.payout_account_id IS '申请时使用的收款账户（V308）';
COMMENT ON COLUMN line_withdraw_request.payee_account_type IS '快照：PAYEE_TYPE_COMPANY|PAYEE_TYPE_PERSONAL（V308）';
COMMENT ON COLUMN line_withdraw_request.payee_account_name IS '快照：户名（对公=公司名）（V308）';
COMMENT ON COLUMN line_withdraw_request.payee_account_no_mask IS '快照：账号掩码（**不得落明文**）（V308）';
COMMENT ON COLUMN line_withdraw_request.idem_key IS '打款幂等键；唯一索引保证同键不重复出款（V308）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_line_withdraw_idem_key
    ON line_withdraw_request (idem_key)
    WHERE idem_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_line_withdraw_payout_account
    ON line_withdraw_request (payout_account_id);

-- ============ 2. 种子：给有余额的线长建一个默认对私微信收款账户 ============
-- ⚠️ 线长主体是**自然人**（佣金分成），故account_type = PAYEE_TYPE_PERSONAL、
--    channel = WECHAT —— 与商户侧的默认对公银行账户形成对照。
--    若线长要提现到银行卡，需在「收款账户」里另建 BANK 通道账户（对私银行卡）。
INSERT INTO payout_account (
    owner_type, owner_id, account_type, channel, account_name,
    account_no, account_no_mask, is_default, status, created_at, updated_at)
SELECT 'LINE_MANAGER', w.manager_id::TEXT, 'PAYEE_TYPE_PERSONAL', 'WECHAT',
       '演示线长（待改为真实实名）',
       'DEMO-ENCRYPTED-PLACEHOLDER', '****0002',
       TRUE, 'ACTIVE', NOW(), NOW()
FROM line_wallet_account w
WHERE w.balance_cents > 0
  AND NOT EXISTS (
      SELECT 1 FROM payout_account a
      WHERE a.owner_type = 'LINE_MANAGER' AND a.owner_id = w.manager_id::TEXT
  );
