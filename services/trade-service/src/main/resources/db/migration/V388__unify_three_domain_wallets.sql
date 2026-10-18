-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low
-- TABLES: wallet_account(新建)、wallet_ledger(新建)；只读源 merchant/line/staff_wallet_account+ledger（演示级行数）
-- COMPETITOR_REF: CB-054,CB-033
-- NOTES: 三域钱包（商户 String merchantId / 线长 long managerId / 补货员 long staffUserId）
--        原为 6 张同构表（每域一张 account + 一张 ledger）。本迁移把「存储 + 写原语」统一到
--        两张多态表 wallet_account / wallet_ledger，用 domain 判别列区分域。
--        范围锁定：只统底层，各域 REST 端点 / DTO / openapi / 后台视图维持现状（facade 层委托，见后续 Java 步）。
--        平台资金池**不并入**（资产侧 vs 负债侧、无冻结/无幂等、PK 粒度不同，并入会退化成 if(domain) 泥球）。
--        owner_id 统一为 VARCHAR(64)：merchant 原样，line/staff 的 BIGINT 以 ::text 落入。
--        数据迁移**只读旧表、不写死演示锚点**：V133/V139 播种的演示余额由通用 SELECT 原样搬运，
--        V344 守卫（仅 prod seed_env=none）先行清演示 ⇒ 非生产迁移后仍带演示行、生产天然干净。
--        frozen_cents：staff 账户列已于 V387 删除（无冻结语义），本域统一记 0，与 StaffWalletService
--        appendLedger 的 frozen_after 恒 0 口径一致。
--        uk_wallet_ledger_ref 用 **partial unique**（沿用 V379）：ref_type<>'WITHDRAW' 豁免提现生命周期多行；
--        domain 前缀保证跨域 owner_id 撞值不可能互相误伤。建索引置于数据搬运**之后**，
--        CREATE UNIQUE INDEX 天然充当「历史重复即当场红」的 DB 级检出（绝不静默去重）。
-- ROLLBACK: 撤销 = DROP TABLE wallet_account, wallet_ledger；旧 6 表在 V389 前仍在，未受影响。

-- ==== 1. 建两张统一多态表 ====
CREATE TABLE IF NOT EXISTS wallet_account (
    domain        VARCHAR(16)  NOT NULL,
    owner_id      VARCHAR(64)  NOT NULL,
    balance_cents BIGINT       NOT NULL DEFAULT 0,
    frozen_cents  BIGINT       NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_wallet_account PRIMARY KEY (domain, owner_id),
    CONSTRAINT ck_wallet_account_domain CHECK (domain IN ('MERCHANT', 'LINE', 'STAFF'))
);

CREATE TABLE IF NOT EXISTS wallet_ledger (
    ledger_id           BIGSERIAL   PRIMARY KEY,
    domain              VARCHAR(16) NOT NULL,
    owner_id            VARCHAR(64) NOT NULL,
    entry_type          VARCHAR(32) NOT NULL,
    amount_cents        BIGINT      NOT NULL,
    balance_after       BIGINT      NOT NULL,
    frozen_after        BIGINT      NOT NULL DEFAULT 0,
    owner_snapshot_name VARCHAR(128),
    ref_type            VARCHAR(32),
    ref_id              VARCHAR(64),
    remark              VARCHAR(255),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_wallet_ledger_domain CHECK (domain IN ('MERCHANT', 'LINE', 'STAFF'))
);

COMMENT ON COLUMN wallet_account.domain IS '钱包域：MERCHANT 商户 / LINE 线长 / STAFF 补货员';
COMMENT ON COLUMN wallet_account.owner_id IS '域内所有者主键：merchant_id 原样，line/staff 的数值 ID 转字符串';
COMMENT ON COLUMN wallet_account.frozen_cents IS '提现冻结额（商户/线长）；STAFF 走线下打款无冻结，恒 0';
COMMENT ON COLUMN wallet_ledger.owner_snapshot_name IS '记账时的所有者名称快照（商户域 V241 merchant_name），线长/补货员为 NULL';

-- ==== 2. 搬运三域账户（只读旧表，不写死演示锚点）====
INSERT INTO wallet_account (domain, owner_id, balance_cents, frozen_cents, updated_at)
SELECT 'MERCHANT', merchant_id, balance_cents, frozen_cents, updated_at
  FROM merchant_wallet_account;

INSERT INTO wallet_account (domain, owner_id, balance_cents, frozen_cents, updated_at)
SELECT 'LINE', manager_id::text, balance_cents, frozen_cents, updated_at
  FROM line_wallet_account;

-- staff_wallet_account.frozen_cents 已由 V387 删除 ⇒ 统一记 0
INSERT INTO wallet_account (domain, owner_id, balance_cents, frozen_cents, updated_at)
SELECT 'STAFF', staff_user_id::text, balance_cents, 0, updated_at
  FROM staff_wallet_account;

-- ==== 3. 搬运三域流水（商户带 merchant_name 快照，线长/补货员 snapshot 为 NULL）====
INSERT INTO wallet_ledger (domain, owner_id, entry_type, amount_cents, balance_after,
                           frozen_after, owner_snapshot_name, ref_type, ref_id, remark, created_at)
SELECT 'MERCHANT', merchant_id, entry_type, amount_cents, balance_after,
       frozen_after, merchant_name, ref_type, ref_id, remark, created_at
  FROM merchant_wallet_ledger;

INSERT INTO wallet_ledger (domain, owner_id, entry_type, amount_cents, balance_after,
                           frozen_after, owner_snapshot_name, ref_type, ref_id, remark, created_at)
SELECT 'LINE', manager_id::text, entry_type, amount_cents, balance_after,
       frozen_after, NULL, ref_type, ref_id, remark, created_at
  FROM line_wallet_ledger;

INSERT INTO wallet_ledger (domain, owner_id, entry_type, amount_cents, balance_after,
                           frozen_after, owner_snapshot_name, ref_type, ref_id, remark, created_at)
SELECT 'STAFF', staff_user_id::text, entry_type, amount_cents, balance_after,
       frozen_after, NULL, ref_type, ref_id, remark, created_at
  FROM staff_wallet_ledger;

-- ==== 4. 建索引（搬运后建：CREATE UNIQUE INDEX 兼作历史重复当场红检出，禁止静默去重）====
-- 幂等兜底：同 (domain, owner_id, ref_type, ref_id) 只允许一条流水；
-- ref_type='WITHDRAW' 天然一 ref 多行（冻结/释放/打款/手续费），partial 排除。
CREATE UNIQUE INDEX IF NOT EXISTS uk_wallet_ledger_ref
    ON wallet_ledger (domain, owner_id, ref_type, ref_id)
    WHERE ref_type IS NOT NULL AND ref_id IS NOT NULL AND ref_type <> 'WITHDRAW';

-- 按域+所有者读账本（分页倒序）
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_owner
    ON wallet_ledger (domain, owner_id, created_at DESC);

-- 按 ref 查询加速（非唯一；uk_wallet_ledger_ref 是 partial，无 ref 行需此索引覆盖按 ref 的检索）
CREATE INDEX IF NOT EXISTS idx_wallet_ledger_ref
    ON wallet_ledger (domain, owner_id, ref_type, ref_id);

COMMENT ON INDEX uk_wallet_ledger_ref IS
    '三域统一钱包流水幂等兜底（V379 同口径并入 domain 前缀）：同请求同 ref 只允许一条流水；提现生命周期 ref_type=WITHDRAW 天然多行故排除';
