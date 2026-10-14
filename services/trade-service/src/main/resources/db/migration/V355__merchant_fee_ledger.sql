-- MIGRATION_KIND: schema
-- COMPETITOR_REF: CB-020
-- 商户费用台账（2026-10-14 拍板：平台一律不自动扣款，所有结算线下进行，系统只做台账记录）。
-- 日结批生成 PENDING 记录；线下收款后运营标记 SETTLED；催缴/锁柜基于 PENDING 未结清累计。
-- 老系统（ego-automat）点位费用同样是「只登记不扣款」（MachinePositionBaseInfo），本表补齐其结清状态闭环。
CREATE TABLE merchant_fee_ledger (
    ledger_id   BIGINT AUTO_INCREMENT PRIMARY KEY,
    merchant_id VARCHAR(32)  NOT NULL,
    fee_date    DATE         NOT NULL COMMENT '费用归属日',
    fee_type    VARCHAR(16)  NOT NULL COMMENT 'REN=租金 / DATA=流量费（费用项可扩展）',
    amount_cents BIGINT      NOT NULL COMMENT '当日应缴费用（分）',
    status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING=待结清 / SETTLED=线下已结清',
    settled_at  TIMESTAMP NULL COMMENT '标记结清时间',
    remark      VARCHAR(200) NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_merchant_date_type (merchant_id, fee_date, fee_type),
    KEY idx_merchant_status (merchant_id, status),
    KEY idx_status_date (status, fee_date)
) COMMENT='商户费用台账（线下结算，只记录不扣款）';

-- 线长（直营）钱包：应扣未扣费用累计（费用行只挂账不动余额，线下结算按净额——口径同商户台账）
ALTER TABLE line_wallet_account
    ADD COLUMN pending_fee_cents BIGINT NOT NULL DEFAULT 0 COMMENT '应扣未扣费用累计（分，日结费用行挂账）';
