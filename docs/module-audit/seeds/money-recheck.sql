-- 资金口径自检（R6 起，可复用）：只读，不写库。任何一条「期望 0」不为 0 都要停下来查。
-- 用法：docker exec -i ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -f - < docs/module-audit/seeds/money-recheck.sql
-- 口径锚点：
--   分账  gross = 冻结实付 − refunded（CommissionBaseCalc.netCents）
--         platform = floor(gross × merchant.platform_rate_bps / 10000)；merchant = gross − platform
--   日账单 credited/pending = Σ split.merchant_cents（按状态分类），platform = Σ split.platform_cents
--   通道费  单独展示（估算 floor(Σgross × fund.channel_fee_bps/10000) 或对账实结），**不从商户入账里扣**
--   钱包    每个 LEDGER_ONLY/SETTLED 分账应有一条 SPLIT_CREDIT，金额 = merchant_cents

\echo '== 1) 分账金额 vs 费率公式（期望 mismatch = 0）=='
SELECT count(*) AS mismatch
FROM order_revenue_split s
JOIN merchant m ON m.merchant_id = s.merchant_id
WHERE s.status NOT IN ('VOIDED', 'REVERSED')
  AND (
        s.platform_cents <> (s.gross_cents * m.platform_rate_bps) / 10000
     OR s.platform_cents + s.merchant_cents <> s.gross_cents
      );

\echo '== 2) 分账毛额 vs 订单净额（期望 mismatch = 0）=='
SELECT count(*) AS mismatch
FROM order_revenue_split s
JOIN cabinet_order o ON o.order_id = s.order_id
WHERE s.status NOT IN ('VOIDED', 'REVERSED')
  AND s.gross_cents <> GREATEST(0, COALESCE(NULLIF(o.paid_amount_cents, 0), o.total_amount_cents) - GREATEST(0, o.refunded_cents));

\echo '== 3) 钱包入账 vs 分账商户份额（期望 mismatch = 0）=='
WITH split_sum AS (
  SELECT merchant_id, SUM(merchant_cents) AS cents
  FROM order_revenue_split
  WHERE status IN ('LEDGER_ONLY', 'SETTLED')
  GROUP BY merchant_id
), wallet_sum AS (
  SELECT owner_id AS merchant_id, SUM(amount_cents) AS cents
  FROM wallet_ledger
  WHERE domain = 'MERCHANT' AND entry_type = 'SPLIT_CREDIT'
  GROUP BY owner_id
)
SELECT count(*) AS mismatch
FROM split_sum s
FULL JOIN wallet_sum w USING (merchant_id)
WHERE COALESCE(s.cents, 0) <> COALESCE(w.cents, 0);

\echo '== 4) 日账单聚合（按 日×商户）与分账明细逐项对（期望 mismatch = 0）=='
-- 页面 /fund-bills 的日行由服务实时聚合；此处独立复算同一口径
WITH b AS (
  SELECT (s.created_at AT TIME ZONE 'Asia/Shanghai')::date AS d, s.merchant_id,
         SUM(s.gross_cents) AS gross,
         SUM(s.platform_cents) AS platform,
         SUM(CASE WHEN s.status IN ('LEDGER_ONLY','SETTLED') THEN s.merchant_cents ELSE 0 END) AS credited,
         SUM(CASE WHEN s.status NOT IN ('LEDGER_ONLY','SETTLED') THEN s.merchant_cents ELSE 0 END) AS pending,
         COUNT(*) AS cnt
  FROM order_revenue_split s
  WHERE s.status NOT IN ('VOIDED', 'REVERSED')
  GROUP BY 1, 2
)
SELECT count(*) AS mismatch FROM b
WHERE gross <> platform + credited + pending OR cnt = 0;

\echo '== 5) 通道费：配置在合法区间（0–1000，超界回落 60）=='
SELECT count(*) AS bad_config
FROM system_config
WHERE config_key = 'fund.channel_fee_bps'
  AND (config_value !~ '^[0-9]+$' OR config_value::int NOT BETWEEN 0 AND 1000);

\echo '== 5b) 通道费估算 vs 对账实结对照（人工可读：估 = floor(Σgross × bps / 10000)，实结优先）=='
WITH cfg AS (
  SELECT (SELECT config_value::int FROM system_config WHERE config_key = 'fund.channel_fee_bps') AS bps
), daily AS (
  SELECT (s.created_at AT TIME ZONE 'Asia/Shanghai')::date AS d, SUM(s.gross_cents) AS gross
  FROM order_revenue_split s
  WHERE s.status NOT IN ('VOIDED', 'REVERSED')
  GROUP BY 1
), actual AS (
  SELECT recon_date AS d, SUM(channel_fee_cents) AS fee
  FROM payment_reconciliation
  WHERE channel_fee_cents IS NOT NULL
  GROUP BY 1
)
SELECT d.d, d.gross, cfg.bps,
       CAST((d.gross * cfg.bps) / 10000 AS bigint) AS estimate_cents,
       a.fee AS actual_cents,
       CAST(COALESCE(a.fee, (d.gross * cfg.bps) / 10000) AS bigint) AS bill_channel_fee_cents
FROM daily d CROSS JOIN cfg LEFT JOIN actual a ON a.d = d.d
ORDER BY d.d DESC;

\echo '== 6) 样例：每份分账的公式复算（人工可读）=='
SELECT s.order_id, s.gross_cents, m.platform_rate_bps AS bps,
       s.platform_cents, (s.gross_cents * m.platform_rate_bps) / 10000 AS platform_expected,
       s.merchant_cents, s.gross_cents - (s.gross_cents * m.platform_rate_bps) / 10000 AS merchant_expected,
       s.status
FROM order_revenue_split s
JOIN merchant m ON m.merchant_id = s.merchant_id
ORDER BY s.created_at DESC
LIMIT 10;
