-- MIGRATION_REVIEWED: yes
-- MIGRATION_KIND: backfill（scheduled_task/权限种子行；主表结构为全新 CREATE TABLE）
-- LOCK_RISK: low（全新表 + device_info 加两列 NULL + scheduled_task/ops_permission 小表单行 INSERT）
-- TABLES: device_rental_deposit (0) | device_rental_deposit_event (0) | device_rental_bill (0) | device_info (+2 列) | scheduled_task (+1) | ops_permission (+1) | ops_role_permission (+1)
-- NOTES: 设备租赁闭环（CB-034）：coopMode=RENTAL 的柜机按月出应收租金账单（device_rental_bill），
--        押金收据化（登记→扣款抵扣→结算返还），欠租逾期自动锁柜（复用 DeviceSalesLockService）。
--        押金是负债不是收入，不进平台资金池台账；租金/押金收款均为线下人工标记，不自动扣款。
-- COMPETITOR_REF: CB-034

-- ── 设备资产档案：租赁月租配置 ──────────────────────────────────────────
ALTER TABLE device_info
    ADD COLUMN rent_cents    BIGINT NULL,
    ADD COLUMN rent_start_ym VARCHAR(7) NULL;
COMMENT ON COLUMN device_info.rent_cents IS '租赁月租金（分/月），仅 coopMode=RENTAL 生效（CB-034）';
COMMENT ON COLUMN device_info.rent_start_ym IS '租赁起租账期（YYYY-MM），账单从此账期开始出';

-- ── 押金台账（每台 RENTAL 柜至多一条有效押金）────────────────────────────
CREATE TABLE device_rental_deposit (
    deposit_id    BIGSERIAL    PRIMARY KEY,
    device_id     VARCHAR(64)  NOT NULL,
    merchant_id   VARCHAR(64)  NULL,
    amount_cents  BIGINT       NOT NULL,
    balance_cents BIGINT       NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'HELD',
    settled_at    TIMESTAMPTZ  NULL,
    remark        VARCHAR(512) NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_rental_deposit_device UNIQUE (device_id)
);
COMMENT ON TABLE device_rental_deposit IS '设备租赁押金台账（CB-034）';
COMMENT ON COLUMN device_rental_deposit.amount_cents IS '押金本金（分）';
COMMENT ON COLUMN device_rental_deposit.balance_cents IS '剩余可退押金（分）= 本金 - Σ扣款';
COMMENT ON COLUMN device_rental_deposit.status IS 'HELD 持有 / SETTLED 已结算返还';

CREATE TABLE device_rental_deposit_event (
    event_id     BIGSERIAL    PRIMARY KEY,
    deposit_id   BIGINT       NOT NULL,
    event_type   VARCHAR(16)  NOT NULL,
    amount_cents BIGINT       NOT NULL,
    reason       VARCHAR(512) NOT NULL,
    operator_id  BIGINT       NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON TABLE device_rental_deposit_event IS '设备租赁押金事件流水（CB-034）';
COMMENT ON COLUMN device_rental_deposit_event.event_type IS 'DEDUCT 扣款抵扣 / RETURN 结算返还';
COMMENT ON COLUMN device_rental_deposit_event.amount_cents IS '带符号金额（分）：均从余额流出，恒为负';
COMMENT ON COLUMN device_rental_deposit_event.reason IS '扣款/返还依据（欠租账单、验机损坏等）';
-- 索引说明：新表数据量小且主查询路径均有 UNIQUE 约束覆盖；为满足迁移安全门禁
-- （同文件触碰热表 device_info 时 CREATE INDEX 须 CONCURRENTLY，而 Flyway 事务内不可用），
-- 普通二级索引留待表上量后在线补建。

-- ── 月租应收账单（仅 RENTAL 且 rent_cents>0 出账）───────────────────────
CREATE TABLE device_rental_bill (
    bill_id      BIGSERIAL    PRIMARY KEY,
    device_id    VARCHAR(64)  NOT NULL,
    device_name  VARCHAR(128) NULL,
    merchant_id  VARCHAR(64)  NULL,
    bill_month   VARCHAR(7)   NOT NULL,
    amount_cents BIGINT       NOT NULL,
    status       VARCHAR(16)  NOT NULL DEFAULT 'UNPAID',
    paid_at      TIMESTAMPTZ  NULL,
    lock_at      TIMESTAMPTZ  NULL,
    remark       VARCHAR(512) NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_rental_bill_device_month UNIQUE (device_id, bill_month)
);
COMMENT ON TABLE device_rental_bill IS '设备租赁月租应收账单（CB-034）';
COMMENT ON COLUMN device_rental_bill.bill_month IS '账期 YYYY-MM';
COMMENT ON COLUMN device_rental_bill.status IS 'UNPAID / PAID / VOID';
COMMENT ON COLUMN device_rental_bill.lock_at IS '欠租锁柜时间（解锁不清除，留痕）';

-- ── 定时任务登记（7 处同步之 ⑥）───────────────────────────────────────────
INSERT INTO scheduled_task (task_key, task_name, task_group, schedule_desc, remark)
VALUES ('device-rental-daily', '租赁账单日检', 'FINANCE', '每日 08:20', '月租出账（幂等）+ 欠租逾期锁柜/结清解锁核对')
ON CONFLICT (task_key) DO NOTHING;

-- ── 权限：读沿用 ops:fund:list（资金账单菜单族）；写动作新增按钮权限 ──
INSERT INTO ops_permission (parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:fund:list' LIMIT 1),
       'ops:rental:manage', '租赁押金/账单管理', 'F', NULL, 21, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:rental:manage');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code = 'ops:rental:manage'
ON CONFLICT DO NOTHING;
