-- V128: remove unused invite-code + commercial hub (储值/入驻/算力); sync menu

-- Member invite code (unused product surface)
DROP INDEX IF EXISTS idx_member_invite_code;
ALTER TABLE member DROP COLUMN IF EXISTS invite_code;

-- Commercial hub tables (API/UI never shipped)
DROP TABLE IF EXISTS recognition_compute_ledger;
DROP TABLE IF EXISTS recognition_compute_account;
DROP TABLE IF EXISTS platform_stored_value_txn;
DROP TABLE IF EXISTS platform_stored_value;
DROP TABLE IF EXISTS merchant_onboarding;

-- Menu sync: remove commercial hub from RBAC tree
DELETE FROM ops_role_permission
WHERE permission_id IN (
    SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:commercial-hub:list'
);
DELETE FROM ops_permission WHERE perm_code = 'ops:commercial-hub:list';

-- Keep message-templates offline (no page/controller)
UPDATE ops_permission
SET status = 'INACTIVE', perm_name = '消息模板(已下线)'
WHERE perm_code IN ('ops:message:templates', 'ops:message:templates:edit');
