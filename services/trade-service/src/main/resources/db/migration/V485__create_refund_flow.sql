-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes（回填 INSERT...SELECT 为聚合口径、与现状月账单等价，批 17 主审）
-- COMPETITOR_REF: CB-061
-- B3（R23 专项，2026-11-16 用户拍板）：退款流水表——MA-271（多次部分退款整笔归最近月）
-- 与 MA-269 残留（扣款成功但 finalize 半段丢失不可回放）的数据模型彻底解。
--
-- 设计要点：
--   * order_id / merchant_id / order_pay_channel 从订单复制（月账单 c1/c2 的「按原收款渠道
--     分桶」口径不变；channel 独立于退款执行方式 refund_method）。
--   * refund_no：渠道退款单号（outRefundNo / requireStableRefundNo 的产物），唯一键做幂等——
--     渠道重试天然稳定复用同一单号（deterministicRefundNo），重复 finalize 不会产生第二行。
--     本地退款（completeLocalRefund / 结算兜底）无单号 ⇒ NULL（PG 唯一索引视 NULL 互异）。
--   * refund_method：退款执行方式（CHANNEL_REFUND / BALANCE / WECHAT_MANUAL /
--     SETTLEMENT_PARTIAL / SETTLEMENT_WAIVE / BACKFILL_AGGREGATE）。
--   * 存量回填（聚合口径，如实）：订单级 refunded_cents 是聚合值，历史逐笔拆分无源数据
--     ——每单回填一笔 BACKFILL_AGGREGATE（amount=refunded_cents、refunded_at=
--     COALESCE(refunded_at, created_at)），归月精度与切换前的月账单完全等价（不比现状差）；
--     新退款开始逐笔精确。
-- LOCK_RISK: low（新表 + 只读回填源）
-- TABLES: refund_flow (new, ~few rows), cabinet_order (read-only in backfill)
-- NOTES: DDL + 回填 INSERT...SELECT；幂等性由 Flyway 一次性执行保证；不改任何历史迁移

CREATE TABLE refund_flow (
    flow_id            BIGSERIAL PRIMARY KEY,
    order_id           VARCHAR(32)  NOT NULL,
    merchant_id        VARCHAR(32)  NOT NULL,
    order_pay_channel  VARCHAR(32)  NOT NULL,
    amount_cents       INTEGER      NOT NULL,
    refund_method      VARCHAR(32)  NOT NULL,
    refund_no          VARCHAR(64)  NULL,
    refunded_at        TIMESTAMPTZ  NOT NULL,
    reason             VARCHAR(500) NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX uq_refund_flow_no ON refund_flow (refund_no) WHERE refund_no IS NOT NULL;
CREATE INDEX idx_refund_flow_merchant_day ON refund_flow (merchant_id, refunded_at);
CREATE INDEX idx_refund_flow_order ON refund_flow (order_id);

-- 存量回填：有退款金额的订单各回填一笔聚合流水（口径与切换前月账单等价）
INSERT INTO refund_flow (order_id, merchant_id, order_pay_channel, amount_cents,
                         refund_method, refund_no, refunded_at, reason)
SELECT o.order_id,
       o.merchant_id,
       COALESCE(NULLIF(TRIM(o.pay_channel), ''), 'UNKNOWN'),
       o.refunded_cents,
       'BACKFILL_AGGREGATE',
       NULL,
       COALESCE(o.refunded_at, o.created_at),
       '存量聚合回填（V485，B3 专项）：切换前退款为订单级聚合，无逐笔源数据'
FROM cabinet_order o
WHERE COALESCE(o.refunded_cents, 0) > 0
  AND o.status <> 'FAILED';
