-- Wave4: site rent split rules + line manager promo task / withdraw tighten columns
CREATE TABLE IF NOT EXISTS site_rent_split_rule (
    rule_id        BIGSERIAL PRIMARY KEY,
    contract_id    BIGINT       NOT NULL REFERENCES site_contract (contract_id) ON DELETE CASCADE,
    party_type     VARCHAR(16)  NOT NULL,
    party_id       VARCHAR(64),
    share_bps      INT          NOT NULL DEFAULT 0,
    fixed_cents    INT          NOT NULL DEFAULT 0,
    status         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    effective_from DATE,
    effective_to   DATE,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_rent_party CHECK (party_type IN ('LANDLORD', 'PLATFORM', 'MERCHANT', 'FRANCHISE', 'OTHER')),
    CONSTRAINT chk_rent_bps CHECK (share_bps >= 0 AND share_bps <= 10000),
    CONSTRAINT chk_rent_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_rent_split_party
    ON site_rent_split_rule (contract_id, party_type, (COALESCE(party_id, '')));

CREATE TABLE IF NOT EXISTS line_promo_task (
    task_id       BIGSERIAL PRIMARY KEY,
    manager_id    BIGINT       NOT NULL,
    title         VARCHAR(128) NOT NULL,
    route_code    VARCHAR(64),
    target_qty    INT          NOT NULL DEFAULT 0,
    done_qty      INT          NOT NULL DEFAULT 0,
    bounty_cents  INT          NOT NULL DEFAULT 0,
    status        VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    due_date      DATE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_line_promo_status CHECK (status IN ('OPEN', 'DONE', 'CANCELLED'))
);
CREATE INDEX IF NOT EXISTS idx_line_promo_manager ON line_promo_task (manager_id, status);

ALTER TABLE line_withdraw_request
    ADD COLUMN IF NOT EXISTS risk_note VARCHAR(256);
