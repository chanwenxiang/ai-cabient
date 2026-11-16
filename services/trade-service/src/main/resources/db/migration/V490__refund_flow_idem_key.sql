-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes（加可空列 + partial unique index；幂等键由写入方派生）
-- COMPETITOR_REF: CB-061（退款流水逐笔台账：渠道退款按 refund_no 幂等，本地退款按累计退款额派生幂等键）
-- B3 追加（2026-11-16，R23 复核）：本地退款（BALANCE / 结算兜底）没有渠道退款单号，
-- 原实现用「同单同额同方法 5 分钟内防重」——这是缓解不是幂等：5 分钟内两笔同额合法部分退
-- 会被合并。现改为真实幂等键：
--   * 渠道退款：idem_key = 'NO:' || refund_no（与既有 refund_no 唯一索引双保险）
--   * 本地退款：idem_key = 'LOCAL:' || order_id || ':' || refund_method || ':' || 累计已退额
-- 累计已退额在调用 record 前已由调用方推进（单调递增）：同一笔退款重放派生同一键；
-- 同额的第二笔合法退款累计额不同 ⇒ 键不同，不会被误合并。
-- 旧行 idem_key 为 NULL（partial unique index 不约束），不回填——历史无逐笔累计快照。
-- LOCK_RISK: low（表行数小）
-- TABLES: refund_flow

ALTER TABLE refund_flow ADD COLUMN IF NOT EXISTS idem_key VARCHAR(160) NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_refund_flow_idem_key
    ON refund_flow (idem_key)
    WHERE idem_key IS NOT NULL;