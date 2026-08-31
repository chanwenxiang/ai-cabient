-- V241: finance display redundancy + UK (no soft-delete on ledgers; Chinese comments)

ALTER TABLE merchant_withdraw_request ADD COLUMN IF NOT EXISTS merchant_name VARCHAR(128);
COMMENT ON COLUMN merchant_withdraw_request.merchant_name IS U&'\5546\6237\540D\79F0\5197\4F59\FF08\63D0\73B0\7533\8BF7\65F6\5199\5165\FF09';

ALTER TABLE merchant_wallet_ledger ADD COLUMN IF NOT EXISTS merchant_name VARCHAR(128);
COMMENT ON COLUMN merchant_wallet_ledger.merchant_name IS U&'\5546\6237\540D\79F0\5197\4F59\FF08\8BB0\8D26\65F6\5199\5165\FF09';

UPDATE merchant_withdraw_request w
SET merchant_name = m.merchant_name
FROM merchant m
WHERE w.merchant_id = m.merchant_id AND w.merchant_name IS NULL;

UPDATE merchant_wallet_ledger l
SET merchant_name = m.merchant_name
FROM merchant m
WHERE l.merchant_id = m.merchant_id AND l.merchant_name IS NULL;

-- line wallet withdraw display if table exists
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='line_withdraw_request') THEN
    EXECUTE 'ALTER TABLE line_withdraw_request ADD COLUMN IF NOT EXISTS manager_name VARCHAR(128)';
    EXECUTE 'COMMENT ON COLUMN line_withdraw_request.manager_name IS U&''\7EBF\8DEF\7ECF\7406\59D3\540D\5197\4F59''';
  END IF;
END $$;

-- recharge external trade nos (partial UK)
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='recharge_order' AND column_name='alipay_trade_no'
  ) THEN
    EXECUTE $i$
      CREATE UNIQUE INDEX IF NOT EXISTS uk_recharge_order_alipay_trade_no
        ON recharge_order (alipay_trade_no)
        WHERE alipay_trade_no IS NOT NULL AND alipay_trade_no <> ''
    $i$;
  END IF;
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema='public' AND table_name='recharge_order' AND column_name='wx_transaction_id'
  ) THEN
    EXECUTE $i$
      CREATE UNIQUE INDEX IF NOT EXISTS uk_recharge_order_wx_transaction_id
        ON recharge_order (wx_transaction_id)
        WHERE wx_transaction_id IS NOT NULL AND wx_transaction_id <> ''
    $i$;
  END IF;
END $$;
