-- 场地租金应付账单：合同月费 × 分账规则出账（人工标记已付，不自动打款）
CREATE TABLE IF NOT EXISTS site_rent_bill (
    bill_id          BIGSERIAL PRIMARY KEY,
    contract_id      BIGINT       NOT NULL REFERENCES site_contract (contract_id) ON DELETE CASCADE,
    device_id        VARCHAR(64)  NOT NULL,
    site_name        VARCHAR(128) NOT NULL,
    bill_month       CHAR(7)      NOT NULL,
    party_type       VARCHAR(16)  NOT NULL,
    party_id         VARCHAR(64),
    share_bps        INT          NOT NULL DEFAULT 0,
    fixed_cents      INT          NOT NULL DEFAULT 0,
    base_fee_cents   INT          NOT NULL DEFAULT 0,
    amount_cents     INT          NOT NULL DEFAULT 0,
    status           VARCHAR(16)  NOT NULL DEFAULT 'UNPAID',
    paid_at          TIMESTAMPTZ,
    remark           VARCHAR(256),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_site_rent_bill_party CHECK (party_type IN ('LANDLORD', 'PLATFORM', 'MERCHANT', 'FRANCHISE', 'OTHER')),
    CONSTRAINT chk_site_rent_bill_bps CHECK (share_bps >= 0 AND share_bps <= 10000),
    CONSTRAINT chk_site_rent_bill_status CHECK (status IN ('UNPAID', 'PAID', 'VOID')),
    CONSTRAINT chk_site_rent_bill_month CHECK (bill_month ~ '^\d{4}-\d{2}$')
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_site_rent_bill_party_month
    ON site_rent_bill (contract_id, bill_month, party_type, (COALESCE(party_id, '')));

CREATE INDEX IF NOT EXISTS idx_site_rent_bill_month_status
    ON site_rent_bill (bill_month, status);

COMMENT ON TABLE site_rent_bill IS '场地租金应付账单（出账台账，不自动打款）';
