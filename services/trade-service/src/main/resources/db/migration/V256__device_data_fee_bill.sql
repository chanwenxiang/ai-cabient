-- 柜机流量费月结应付账单（台账；标记已付不自动扣款）
CREATE TABLE IF NOT EXISTS device_data_fee_bill (
    bill_id       BIGSERIAL PRIMARY KEY,
    device_id     VARCHAR(64)  NOT NULL,
    device_name   VARCHAR(128),
    merchant_id   VARCHAR(64),
    bill_month    CHAR(7)      NOT NULL,
    amount_cents  INT          NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL DEFAULT 'UNPAID',
    paid_at       TIMESTAMPTZ,
    remark        VARCHAR(256),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_device_data_fee_bill_status CHECK (status IN ('UNPAID', 'PAID', 'VOID')),
    CONSTRAINT chk_device_data_fee_bill_month CHECK (bill_month ~ '^\d{4}-\d{2}$'),
    CONSTRAINT chk_device_data_fee_bill_amount CHECK (amount_cents >= 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_device_data_fee_bill_month
    ON device_data_fee_bill (device_id, bill_month)
    WHERE status <> 'VOID';

CREATE INDEX IF NOT EXISTS idx_device_data_fee_bill_month_status
    ON device_data_fee_bill (bill_month, status);

COMMENT ON TABLE device_data_fee_bill IS '柜机流量费月结应付账单（出账台账，不自动扣款）';

INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, enabled, remark, updated_at)
VALUES (
    'ops-fee-bill-monthly',
    '周期费用月结出账',
    'FINANCE',
    '由 aicabinet.fee-bill.auto-generate-cron 配置',
    TRUE,
    '场地租金 + 柜机流量费；账期偏移见 fee-bill.bill-month-offset-months',
    NOW()
)
ON CONFLICT (task_key) DO NOTHING;
