-- MIGRATION_REVIEWED: yes
-- MIGRATION_KIND: backfill（scheduled_task/权限种子行；主表结构为全新 CREATE TABLE）
-- LOCK_RISK: low（全新表 + scheduled_task/ops_permission 小表单行 INSERT）
-- TABLES: platform_fund_account (0) | platform_fund_flow (0) | scheduled_task (+1) | ops_permission (+1) | ops_role_permission (+1)
-- NOTES: 平台资金池台账（CB-033）：登记各出款渠道账户余额（充值/归集/调整），提现打款成功自动记流出；
--        提现前按渠道校验「余额 ≥ 当日待付净额」，不足拦截打款转人工（未登记账户软启动只告警）。
-- COMPETITOR_REF: CB-033
-- FIX(2026-10-12): 初稿误用 MySQL 方言（AUTO_INCREMENT/COMMENT/ENGINE/ON UPDATE）在 PG 报 42601，迁移回滚、trade 重启循环；
--                  按 V336 惯例改写 PG 方言（BIGSERIAL/TIMESTAMPTZ/COMMENT ON/独立 CREATE INDEX），列与语义不变。迁移从未成功应用，原地重写不违反 checksum 铁律。

-- ── 平台资金账户（每个打款渠道一行台账账户，channel 唯一）─────────────────
CREATE TABLE platform_fund_account (
    account_id     BIGSERIAL    PRIMARY KEY,
    channel        VARCHAR(32)  NOT NULL,
    account_name   VARCHAR(128) NOT NULL,
    balance_cents  BIGINT       NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    remark         VARCHAR(512) NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_platform_fund_channel UNIQUE (channel)
);
COMMENT ON TABLE platform_fund_account IS '平台资金池账户台账（CB-033）';
COMMENT ON COLUMN platform_fund_account.channel IS '出款渠道：WECHAT/ALIPAY/BANK（与 payout_account.channel 同口径）';
COMMENT ON COLUMN platform_fund_account.account_name IS '账户名（如：微信运营账户）';
COMMENT ON COLUMN platform_fund_account.balance_cents IS '当前余额（分），由 flow 流水推进';
COMMENT ON COLUMN platform_fund_account.status IS 'ACTIVE / INACTIVE';

-- ── 平台资金流水（余额唯一真源是本表推进，账户行为缓存）────────────────────
CREATE TABLE platform_fund_flow (
    flow_id             BIGSERIAL    PRIMARY KEY,
    account_id          BIGINT       NOT NULL,
    flow_type           VARCHAR(16)  NOT NULL,
    amount_cents        BIGINT       NOT NULL,
    balance_after_cents BIGINT       NOT NULL,
    ref_type            VARCHAR(32)  NULL,
    ref_id              VARCHAR(64)  NULL,
    operator_id         BIGINT       NULL,
    remark              VARCHAR(512) NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE platform_fund_flow IS '平台资金池流水（CB-033）';
COMMENT ON COLUMN platform_fund_flow.flow_type IS 'RECHARGE 充值 / SWEEP 归集流出 / ADJUST 调整 / PAYOUT 提现打款流出';
COMMENT ON COLUMN platform_fund_flow.amount_cents IS '带符号金额（分）：入账正、出账负';
COMMENT ON COLUMN platform_fund_flow.balance_after_cents IS '流水后余额（分）';
COMMENT ON COLUMN platform_fund_flow.ref_type IS '关联业务类型（如 MERCHANT_WITHDRAW）';
COMMENT ON COLUMN platform_fund_flow.ref_id IS '关联业务单号/ID';
COMMENT ON COLUMN platform_fund_flow.operator_id IS '操作人（系统写入为 NULL）';
CREATE INDEX idx_platform_fund_flow_account ON platform_fund_flow (account_id, created_at);
CREATE INDEX idx_platform_fund_flow_ref ON platform_fund_flow (ref_type, ref_id);
CREATE INDEX idx_platform_fund_flow_type_time ON platform_fund_flow (flow_type, created_at);

-- ── 定时任务登记（7 处同步之 ⑥）───────────────────────────────────────────
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark)
VALUES ('platform-fund-daily', '平台资金日检', 'FINANCE', '每日 08:10', '备付金计划推送 + 归集交叉核对')
ON CONFLICT (task_key) DO NOTHING;

-- ── 权限：查看沿用既有 ops:fund:list（资金账单菜单族）；写动作新增按钮权限 ──
INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:fund:list' LIMIT 1),
       'ops:fund:manage', '平台资金台账登记', 'F', NULL, 20, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:fund:manage');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code = 'ops:fund:manage'
ON CONFLICT DO NOTHING;
