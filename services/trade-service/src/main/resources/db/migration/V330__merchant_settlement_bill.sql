-- COMPETITOR_REF: CB-020
-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- V330（CB-020 落码 ②）：商户月度结算单物理表。
-- 现状：商户端「结算批次」= order_revenue_split 按 settlement_batch_no 的聚合视图
--   （OrderRevenueSplitMapper.xml selectAggregateBatchByMerchants），而 batchNo 在
--   split 行创建时即逐行独立发号（RevenueSplitService 创建路径）——所谓批次实际
--   逐单一号，无对账单形态。
-- 本表落「商户×账期月」唯一结算单 + 快照金额，对齐友宝「每月正式结算」节奏
--   （docs/COMPETITOR_BENCHMARK.md CB-020 结论②，同 CB-017 月结单实体化思路）。
-- 口径：账期月 = split.created_at 的 Asia/Shanghai 日历月（与商户端既有日结算/
--   批次视图的 created_at 窗口一致，可对平）；排除退款冲正单（VOIDED/REVERSED，
--   全额退款/分账回退后的冲正行，旧聚合视图未剔除属口径瑕疵，新单据不延续）；
--   settled = status='SUCCESS'，pending = 其余有效状态，failedCount 只数
--   WECHAT_FAILED/FAILED。重建即真相：幂等整月覆盖，已 CONFIRMED 行只刷新
--   金额不动状态；本月无有效 split 的陈旧行删除。
-- 竞品：友宝招股书「每月银行转账结算」月度对账单形态（CB-020 证据链）。

CREATE TABLE merchant_settlement_bill (
    merchant_id    VARCHAR(64) NOT NULL,
    period_month   DATE        NOT NULL,
    bill_no        VARCHAR(64) NOT NULL,
    status         VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    order_count    INT         NOT NULL DEFAULT 0,
    gross_cents    BIGINT      NOT NULL DEFAULT 0,
    platform_cents BIGINT      NOT NULL DEFAULT 0,
    merchant_cents BIGINT      NOT NULL DEFAULT 0,
    settled_cents  BIGINT      NOT NULL DEFAULT 0,
    pending_cents  BIGINT      NOT NULL DEFAULT 0,
    failed_count   INT         NOT NULL DEFAULT 0,
    computed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    confirmed_at   TIMESTAMPTZ,
    PRIMARY KEY (merchant_id, period_month)
);

CREATE UNIQUE INDEX idx_merchant_settlement_bill_no
    ON merchant_settlement_bill (bill_no);

CREATE INDEX idx_merchant_settlement_bill_period
    ON merchant_settlement_bill (period_month, merchant_id);

COMMENT ON TABLE merchant_settlement_bill IS '商户月度结算单（CB-020）：merchant×账期月唯一快照，幂等重建，PENDING/CONFIRMED';
COMMENT ON COLUMN merchant_settlement_bill.period_month IS '账期月首日（Asia/Shanghai 日历月），如 2026-10-01';
COMMENT ON COLUMN merchant_settlement_bill.bill_no IS '结算单号，确定性生成 SB+yyyyMM-商户号（同月同商户重建不换号）';
