-- V240: ops / repair display redundancy (Chinese comments)

ALTER TABLE repair_ticket ADD COLUMN IF NOT EXISTS device_name VARCHAR(128);
COMMENT ON COLUMN repair_ticket.device_name IS U&'\8BBE\5907\540D\79F0\5197\4F59';

ALTER TABLE repair_ticket ADD COLUMN IF NOT EXISTS merchant_id VARCHAR(64);
COMMENT ON COLUMN repair_ticket.merchant_id IS U&'\5546\6237ID\5197\4F59';

ALTER TABLE repair_ticket ADD COLUMN IF NOT EXISTS merchant_name VARCHAR(128);
COMMENT ON COLUMN repair_ticket.merchant_name IS U&'\5546\6237\540D\79F0\5197\4F59';

CREATE INDEX IF NOT EXISTS idx_repair_ticket_merchant_id ON repair_ticket (merchant_id);

UPDATE repair_ticket t
SET device_name = d.device_name,
    merchant_id = d.merchant_id,
    merchant_name = m.merchant_name
FROM device_info d
LEFT JOIN merchant m ON m.merchant_id = d.merchant_id
WHERE t.device_id = d.device_id
  AND (t.device_name IS NULL OR t.merchant_id IS NULL OR t.merchant_name IS NULL);

-- device_fault_report if present: display redundancy
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema='public' AND table_name='device_fault_report') THEN
    EXECUTE 'ALTER TABLE device_fault_report ADD COLUMN IF NOT EXISTS device_name VARCHAR(128)';
    EXECUTE 'COMMENT ON COLUMN device_fault_report.device_name IS U&''\8BBE\5907\540D\79F0\5197\4F59''';
    IF EXISTS (
      SELECT 1 FROM information_schema.columns
      WHERE table_schema='public' AND table_name='device_fault_report' AND column_name='device_id'
    ) THEN
      EXECUTE $u$
        UPDATE device_fault_report r
        SET device_name = d.device_name
        FROM device_info d
        WHERE r.device_id = d.device_id AND r.device_name IS NULL
      $u$;
    END IF;
  END IF;
END $$;
