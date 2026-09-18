-- H30(b)：site_rent_bill 唯一索引 uk_site_rent_bill_party_month 不含 status，
-- VOID 单仍占用 (contract_id, bill_month, party_type, party_id) 键，导致作废后重出账必然 UK 冲突。
-- 降级为普通索引；防重出账改由 countNonVoidByContractAndMonth 在
-- 分布式锁(site-rent-bill:{contract}:{month}) + 合同行锁(FOR UPDATE) 内应用层保证。

DROP INDEX IF EXISTS uk_site_rent_bill_party_month;

CREATE INDEX IF NOT EXISTS idx_site_rent_bill_party_month
    ON site_rent_bill (contract_id, bill_month, party_type, (COALESCE(party_id, '')));
