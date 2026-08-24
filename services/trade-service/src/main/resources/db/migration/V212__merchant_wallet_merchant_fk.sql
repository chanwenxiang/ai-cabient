-- Merchant child tables must reference merchant.merchant_id (E2E leftover MCH-DVT-* wallets).
-- 1) Purge orphan rows.
-- 2) Add FK: wallet/config/onboarding CASCADE; withdraw RESTRICT (keep payout audit if merchant removed manually).

DELETE FROM merchant_wallet_ledger l
WHERE NOT EXISTS (
    SELECT 1 FROM merchant m WHERE m.merchant_id = l.merchant_id
);

DELETE FROM merchant_wallet_account a
WHERE NOT EXISTS (
    SELECT 1 FROM merchant m WHERE m.merchant_id = a.merchant_id
);

DELETE FROM merchant_ops_config c
WHERE NOT EXISTS (
    SELECT 1 FROM merchant m WHERE m.merchant_id = c.merchant_id
);

DELETE FROM merchant_payment_onboarding o
WHERE NOT EXISTS (
    SELECT 1 FROM merchant m WHERE m.merchant_id = o.merchant_id
);

DELETE FROM merchant_withdraw_request w
WHERE NOT EXISTS (
    SELECT 1 FROM merchant m WHERE m.merchant_id = w.merchant_id
);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'merchant_wallet_account_merchant_id_fkey'
          AND conrelid = 'merchant_wallet_account'::regclass
    ) THEN
        ALTER TABLE merchant_wallet_account
            ADD CONSTRAINT merchant_wallet_account_merchant_id_fkey
            FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'merchant_wallet_ledger_merchant_id_fkey'
          AND conrelid = 'merchant_wallet_ledger'::regclass
    ) THEN
        ALTER TABLE merchant_wallet_ledger
            ADD CONSTRAINT merchant_wallet_ledger_merchant_id_fkey
            FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'merchant_ops_config_merchant_id_fkey'
          AND conrelid = 'merchant_ops_config'::regclass
    ) THEN
        ALTER TABLE merchant_ops_config
            ADD CONSTRAINT merchant_ops_config_merchant_id_fkey
            FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'merchant_payment_onboarding_merchant_id_fkey'
          AND conrelid = 'merchant_payment_onboarding'::regclass
    ) THEN
        ALTER TABLE merchant_payment_onboarding
            ADD CONSTRAINT merchant_payment_onboarding_merchant_id_fkey
            FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id)
            ON DELETE CASCADE;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'merchant_withdraw_request_merchant_id_fkey'
          AND conrelid = 'merchant_withdraw_request'::regclass
    ) THEN
        ALTER TABLE merchant_withdraw_request
            ADD CONSTRAINT merchant_withdraw_request_merchant_id_fkey
            FOREIGN KEY (merchant_id) REFERENCES merchant (merchant_id)
            ON DELETE RESTRICT;
    END IF;
END $$;
