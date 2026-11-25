-- COMPETITOR_REF: CB-033
-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- TABLES: platform_fund_flow
-- LOCK_RISK: low（建 partial unique 索引；写入锁仅覆盖提现流水，通常行数很少）
-- ROLLBACK: DROP INDEX uk_platform_fund_flow_payout_ref
-- NOTES: 商户提现回调与定时查单可能同时到达；同一提现只能登记一条 PAYOUT 流水。
--        人工充值/归集/调整没有业务引用，不受此唯一约束限制。

DO $$
BEGIN
    IF EXISTS (
        SELECT ref_type, ref_id
        FROM platform_fund_flow
        WHERE flow_type = 'PAYOUT'
          AND ref_type = 'MERCHANT_WITHDRAW'
          AND ref_id IS NOT NULL
        GROUP BY ref_type, ref_id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION 'platform_fund_flow 存在重复提现流水，请先人工核对后再创建唯一索引';
    END IF;
END
$$;

CREATE UNIQUE INDEX uk_platform_fund_flow_payout_ref
    ON platform_fund_flow (ref_type, ref_id)
    WHERE flow_type = 'PAYOUT'
      AND ref_type = 'MERCHANT_WITHDRAW'
      AND ref_id IS NOT NULL;
