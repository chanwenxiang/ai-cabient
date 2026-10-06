-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: payout_account (新建，初始 0 行)；merchant_withdraw_request (+8 列，演示数十行；生产按商户数)
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = 删除 payout_account 表；并撤销 merchant_withdraw_request 上新增的 8 个列
-- NOTES: 提现收款方模型（接真实支付认证前的结构性铺垫，当前仍走 MOCK 记账打款）。
--   1) payout_account：一个主体可挂多个收款账户（对公/对私并存），支持设默认、可停用；
--      敏感字段（account_no）密文入库，account_no_mask 只存掩码供列表展示。
--      一个主体同 account_type 至多一个 DEFAULT（部分唯一索引 uk_payout_account_default）。
--   2) merchant_withdraw_request 补 8 列：提现单在**申请时快照**收款方与通道，
--      避免「打款时账户被改/被停用」导致出款对象漂移（金融口径：下单即锁定收款人）。
--      payee_* 为快照（历史不可变）；payout_account_id 指向当时的账户。
--   3) idem_key：打款幂等键，防「渠道已出款但本地回执丢失」导致重试重复出款。
--      唯一索引保证同幂等键不会发起两次打款。
--   4) channel_order_no / channel_batch_no：渠道单号与批次号，供对账与人工核单。
--   新列全部可空 ⇒ 存量行（已 PAID 的历史提现）无需回填即可兼容。
--   并发：仅 ADD COLUMN + CREATE TABLE + CREATE INDEX，不与事务型语句混用；
--   演示库小表可短锁，生产商户数增长后建议低峰执行。

-- ============ 1. 收款账户表 ============
CREATE TABLE IF NOT EXISTS payout_account (
    account_id        BIGSERIAL PRIMARY KEY,
    -- 主体类型：MERCHANT（商户）| LINE_MANAGER（线长）| PLATFORM（平台）
    owner_type        VARCHAR(32)  NOT NULL,
    owner_id          VARCHAR(64)  NOT NULL,
    -- 对公 PRIVATE / 对私 PUBLIC？→ 语义相反易错，这里用显式枚举：
    -- PAYEE_TYPE_COMPANY（对公）/ PAYEE_TYPE_PERSONAL（对私）
    account_type      VARCHAR(32)  NOT NULL,
    -- 渠道：WECHAT / ALIPAY / BANK
    channel           VARCHAR(32)  NOT NULL,
    -- 户名（对公=公司名，对私=实名）
    account_name      VARCHAR(128) NOT NULL,
    -- 账号密文（AES-GCM，密钥走 aicabinet.payout-encryption.key；密文格式 iv:ct:tag）
    account_no        TEXT        NOT NULL,
    -- 掩码（明文派生，仅供列表/对账展示，不可逆）
    account_no_mask   VARCHAR(64)  NOT NULL,
    -- 开户银行（对公必填）/ 支行（可选）
    bank_name         VARCHAR(128),
    bank_branch       VARCHAR(128),
    -- 纳税人识别号（对公必填，渠道代付要求）
    tax_no            VARCHAR(64),
    -- 是否该主体的默认收款账户
    is_default        BOOLEAN     NOT NULL DEFAULT FALSE,
    -- ACTIVE / DISABLED（停用后不可用于新提现单，历史单不受影响）
    status            VARCHAR(32)  NOT NULL DEFAULT 'ACTIVE',
    -- 乐观锁版本号，防止并发改账户
    version           INT         NOT NULL DEFAULT 0,
    created_by        BIGINT,
    reviewed_by       BIGINT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE payout_account IS '提现收款账户（一个主体可多个；敏感账号密文入库，列表只出掩码）';
COMMENT ON COLUMN payout_account.owner_type IS 'MERCHANT|LINE_MANAGER|PLATFORM';
COMMENT ON COLUMN payout_account.account_type IS 'PAYEE_TYPE_COMPANY（对公）|PAYEE_TYPE_PERSONAL（对私）';
COMMENT ON COLUMN payout_account.account_no IS 'AES-GCM 密文（iv:ciphertext:tag），非明文';
COMMENT ON COLUMN payout_account.account_no_mask IS '掩码（明文派生，不可逆），仅供展示';

CREATE INDEX IF NOT EXISTS idx_payout_account_owner
    ON payout_account (owner_type, owner_id, status);
-- 同一主体同账户类型至多一个默认账户（Postgres 部分唯一索引）
CREATE UNIQUE INDEX IF NOT EXISTS uk_payout_account_default
    ON payout_account (owner_type, owner_id, account_type)
    WHERE is_default AND status = 'ACTIVE';

-- ============ 2. 提现单补收款方快照 + 幂等键 ============
ALTER TABLE merchant_withdraw_request
    ADD COLUMN IF NOT EXISTS payout_account_id BIGINT;

-- 以下 5 列为**申请时快照**，历史不可变（账户后续被改/停用不影响已成立的提现单）
ALTER TABLE merchant_withdraw_request
    ADD COLUMN IF NOT EXISTS payee_account_type VARCHAR(32);
ALTER TABLE merchant_withdraw_request
    ADD COLUMN IF NOT EXISTS payee_account_name VARCHAR(128);
ALTER TABLE merchant_withdraw_request
    ADD COLUMN IF NOT EXISTS payee_account_no_mask VARCHAR(64);
ALTER TABLE merchant_withdraw_request
    ADD COLUMN IF NOT EXISTS payee_bank_name VARCHAR(128);
ALTER TABLE merchant_withdraw_request
    ADD COLUMN IF NOT EXISTS payee_tax_no VARCHAR(64);

-- 打款幂等键：渠道已出款但本地回执丢失时，重试复用同键 ⇒ 渠道侧不会重复出款
ALTER TABLE merchant_withdraw_request
    ADD COLUMN IF NOT EXISTS idem_key VARCHAR(96);
-- 渠道单号/批次号：供对账与人工核单
ALTER TABLE merchant_withdraw_request
    ADD COLUMN IF NOT EXISTS channel_order_no VARCHAR(128);
ALTER TABLE merchant_withdraw_request
    ADD COLUMN IF NOT EXISTS channel_batch_no VARCHAR(128);

COMMENT ON COLUMN merchant_withdraw_request.payout_account_id IS '申请时使用的收款账户（外键指向当时账户）';
COMMENT ON COLUMN merchant_withdraw_request.payee_account_type IS '快照：PAYEE_TYPE_COMPANY|PAYEE_TYPE_PERSONAL';
COMMENT ON COLUMN merchant_withdraw_request.payee_account_name IS '快照：户名（对公=公司名）';
COMMENT ON COLUMN merchant_withdraw_request.payee_account_no_mask IS '快照：账号掩码（**不得落明文**）';
COMMENT ON COLUMN merchant_withdraw_request.idem_key IS '打款幂等键；唯一索引保证同键不重复出款';

CREATE UNIQUE INDEX IF NOT EXISTS uk_merchant_withdraw_idem_key
    ON merchant_withdraw_request (idem_key)
    WHERE idem_key IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_merchant_withdraw_payout_account
    ON merchant_withdraw_request (payout_account_id);

-- ============ 3. 种子：给有余额的演示商户建一个默认对公账户 ============
-- ⚠️ 商户 ID 是<b>数字字符串</b>（如 227228227182），不是 'MCH-DEFAULT' ——
--    早期种子脚本按 'MCH-DEFAULT' 写死，导致这里若沿用会一条都插不进去。
--    改为「取有余额的商户」，与 merchant_wallet_account 的实际数据对齐。
-- 🔴 account_no 是明文占位符（PayoutFieldCipher 对 DEMO-ENCRYPTED-PLACEHOLDER 前缀原样保留，
--    不产生伪密文）⇒ 便于识别「这条不是真账号」。生产必须走 admin 接口写入真密文。
INSERT INTO payout_account (
    owner_type, owner_id, account_type, channel, account_name,
    account_no, account_no_mask, bank_name, tax_no, is_default, status, created_at, updated_at)
SELECT 'MERCHANT', w.merchant_id, 'PAYEE_TYPE_COMPANY', 'BANK',
       '演示商户（待改为真实户名）',
       'DEMO-ENCRYPTED-PLACEHOLDER', '****0001',
       '演示银行（待改为真实开户行）', 'DEMO-TAX-NO', TRUE, 'ACTIVE', NOW(), NOW()
FROM merchant_wallet_account w
WHERE w.balance_cents > 0
  AND NOT EXISTS (
      SELECT 1 FROM payout_account a
      WHERE a.owner_type = 'MERCHANT' AND a.owner_id = w.merchant_id
  );
