-- MIGRATION_KIND: schema
-- MIGRATION_REVIEWED: yes
-- LOCK_RISK: medium（建 partial unique index 需扫描 platform_fund_flow；重复数据会明确失败，不做静默去重）
-- TABLES: platform_fund_flow
-- COMPETITOR_REF: CB-033
-- NOTES: MA-442：PAYOUT 按 requestId、微信资金账单按 (ref_type, ref_id) 数据库级幂等。
CREATE UNIQUE INDEX IF NOT EXISTS uq_platform_fund_payout_ref
  ON platform_fund_flow (ref_id)
  WHERE flow_type = 'PAYOUT' AND ref_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_platform_fund_bill_ref
  ON platform_fund_flow (ref_type, ref_id)
  WHERE ref_type = 'WECHAT_FUND_BILL' AND ref_id IS NOT NULL;
