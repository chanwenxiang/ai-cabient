-- V242: index rename to uk_/idx_ convention (batch on hot tables)
-- Boolean is_ rename deferred for high-risk columns; see docs/db-conventions.md

ALTER INDEX IF EXISTS payment_operation_idempotency_key_key RENAME TO uk_payment_operation_idempotency_key;
ALTER INDEX IF EXISTS shopping_session_idempotency_key_key RENAME TO uk_shopping_session_idempotency_key;

-- unique phone historically named idx_
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
             WHERE n.nspname='public' AND c.relname='idx_user_phone' AND c.relkind='i') THEN
    ALTER INDEX idx_user_phone RENAME TO uk_user_phone;
  END IF;
END $$;

-- document exemption: verified/enabled/sales_locked keep legacy names (no column rename)
COMMENT ON COLUMN user_info.verified IS U&'\662F\5426\5B9E\540D\9A8C\8BC1\FF08\5386\53F2\5217\540D\FF0C\8C41\514D\6539\4E3Ais_verified\FF09';
