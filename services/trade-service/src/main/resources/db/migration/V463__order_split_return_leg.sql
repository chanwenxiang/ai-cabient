-- MIGRATION_KIND: backfill
-- COMPETITOR_REF: CB-085,CB-053,CB-057
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: low（新建空表 + 从 36 行分账单里回填 0 行；无 ALTER/DROP，不碰热表）
-- TABLES: order_revenue_split (dev 36 行 / 其中 pending 0 行，生产按量级评估)、order_split_return_leg (新建空表)
-- NOTES: R20-9（四端审计 2026-11-12，docs/AUDIT_FOUR_END_R20_2026-11-12.md §十九）。
--        🔴 资损修复：wechat_pending_return_no / _cents 是 split 上的一对单值列，
--        applyWeChatReturnOutcome 每次覆盖。100 元单先部分退 30、再部分退 20、最后全额退，
--        三笔渠道回退只有最后一笔留在本地账 ⇒ 渠道合计只回退 70，商户多留 30。
--        更重的是 ProfitSharingReturnCompensationService 以 splitId 作 CompensationTask.txId
--        并用 existsPending(splitId,…) 去重 ⇒ 同一 split 只允许一个 PENDING 补偿任务，
--        第二轮回退连查单/重提的调度槽都被吞。
--        本表按「一笔渠道回退单 = 一条腿」立账（CB-053 微信单笔部分退款 ≤50 次、
--        CB-057 支付宝「发生退款时产生新的记录」/老系统财务退款明细表同构）。
--        out_return_no UNIQUE 是协议幂等键：同一单号重报走 upsert，绝不合并多轮为新单号
--        （前一条腿可能已在渠道在途，换号 = 双退）。
--        存量单值列**不删除**：降级为「最老一条未完结腿」的派生投影，供既有报表/对账读取；
--        所有 Java 资金动作改为只读腿表。
--        dev 库前置核对（2026-11-12 实跑）：
--          SELECT COUNT(*) FROM order_revenue_split;                                  → 36
--          SELECT COUNT(*) FROM order_revenue_split
--            WHERE wechat_pending_return_no IS NOT NULL AND <> '';                  → 0
--          ⇒ 本次回填预期 0 行；生产上线前须按同一 SQL 核对应迁移行数。
--        索引口径（铁律 88①）：partial index 谓词是 status IN ('PENDING','PROCESSING','FAILED')，
--        查开放腿的 SQL 必须带完整谓词才能命中；status='SUCCESS' 的守恒聚合不蕴含该谓词，
--        走普通 (split_id, status) 索引，不是「缺索引」。

CREATE TABLE IF NOT EXISTS order_split_return_leg (
    leg_id          BIGSERIAL PRIMARY KEY,
    split_id        VARCHAR(32)  NOT NULL REFERENCES order_revenue_split(split_id) ON DELETE CASCADE,
    out_return_no   VARCHAR(128) NOT NULL UNIQUE,
    cents           BIGINT       NOT NULL CHECK (cents > 0),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
                    CHECK (status IN ('PENDING','PROCESSING','SUCCESS','FAILED')),
    failure_reason  VARCHAR(512),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_split_return_leg_open
    ON order_split_return_leg (split_id, leg_id)
    WHERE status IN ('PENDING','PROCESSING','FAILED');

CREATE INDEX IF NOT EXISTS idx_split_return_leg_split_status
    ON order_split_return_leg (split_id, status);

-- 存量回填：单值列里那一笔未完结回退，按「最老一条腿」落成独立行。
-- ON CONFLICT (out_return_no) DO NOTHING 保证重复执行/多环境重放不会插出第二条腿。
INSERT INTO order_split_return_leg
    (split_id, out_return_no, cents, status, failure_reason, created_at, updated_at)
SELECT s.split_id,
       s.wechat_pending_return_no,
       s.wechat_pending_return_cents,
       CASE WHEN s.failure_reason LIKE '%分账回退未成功%' THEN 'FAILED' ELSE 'PROCESSING' END,
       s.failure_reason,
       COALESCE(s.created_at, NOW()),
       NOW()
FROM order_revenue_split s
WHERE s.wechat_pending_return_no IS NOT NULL
  AND s.wechat_pending_return_no <> ''
  AND s.wechat_pending_return_cents IS NOT NULL
  AND s.wechat_pending_return_cents > 0
ON CONFLICT (out_return_no) DO NOTHING;
