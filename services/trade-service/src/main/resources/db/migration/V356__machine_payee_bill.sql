-- MIGRATION_KIND: backfill（DDL + 出账任务种子行随迁移登记）
-- COMPETITOR_REF: CB-020
-- 机器账单（付场地方，2026-10-14 拍板）：对齐老系统 MachinePositionBaseInfo 点位台账模型
-- （电费类型+费率、客情、分成收款人），并补齐其缺失的「出账+结清」闭环。
-- 场地方不是系统用户，收款人为文本快照；线下结算，系统只做台账。
-- PostgreSQL 方言（2026-10-14 修正：初版误写 MySQL 语法，未在任何库应用过，原位改写）。
CREATE TABLE machine_position_config (
    device_id             VARCHAR(32) PRIMARY KEY,
    electric_type         VARCHAR(16) NOT NULL DEFAULT 'NONE', -- NONE/FIXED（按量暂缓，无计量）
    electric_amount_cents BIGINT      NOT NULL DEFAULT 0,      -- 月电费（固定额，分）
    welfare_info          VARCHAR(200) NULL,                   -- 客情说明（送水/其他，线下处理）
    share_bps             INT         NOT NULL DEFAULT 0,      -- 场地方销售分成比例（bps）
    payee_name            VARCHAR(64) NOT NULL,                -- 场地方收款人姓名
    payee_phone           VARCHAR(20) NULL,
    enabled               INT         NOT NULL DEFAULT 1,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()   -- 应用层维护（保存时刷新）
);

CREATE TABLE machine_payee_bill (
    bill_id      BIGSERIAL PRIMARY KEY,
    device_id    VARCHAR(32) NOT NULL,
    bill_month   VARCHAR(7)  NOT NULL, -- 账期 yyyy-MM（出上月账）
    fee_type     VARCHAR(16) NOT NULL, -- ELECTRIC=电费 / SHARE=销售分成
    amount_cents BIGINT      NOT NULL,
    gmv_cents    BIGINT      NULL,     -- 分成计算基数（SHARE 类）
    payee_name   VARCHAR(64) NOT NULL,
    payee_phone  VARCHAR(20) NULL,
    status       VARCHAR(16) NOT NULL DEFAULT 'PENDING', -- PENDING/SETTLED（线下结算标记）
    settled_at   TIMESTAMPTZ NULL,
    remark       VARCHAR(200) NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_machine_bill_month_type
    ON machine_payee_bill (device_id, bill_month, fee_type);
CREATE INDEX IF NOT EXISTS idx_machine_bill_status_month
    ON machine_payee_bill (status, bill_month);

INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark) VALUES
('machine-payee-bill', '机器账单出账（付场地方）', 'TRADE', '每月 1 日 01:10',
 '每月 1 日出上月机器账单：电费（点位配置固定额）+ 销售分成（share_bps×上月该柜已支付 GMV）；收款人为场地方文本快照，线下结算后运营标记 SETTLED')
ON CONFLICT (task_key) DO NOTHING;
