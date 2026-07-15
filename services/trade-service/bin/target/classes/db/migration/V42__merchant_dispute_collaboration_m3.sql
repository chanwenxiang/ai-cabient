-- M3 商户争议协同：多轮留言 + 回复权限

CREATE TABLE IF NOT EXISTS dispute_message (
    message_id   BIGSERIAL PRIMARY KEY,
    ticket_id    VARCHAR(32)  NOT NULL REFERENCES dispute_ticket(ticket_id),
    author_type  VARCHAR(16)  NOT NULL,
    author_id    BIGINT,
    body         VARCHAR(1024) NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_dispute_message_ticket ON dispute_message (ticket_id, created_at);

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (222, 200, 'merchant:disputes:reply', '争议回复', 'C', '/merchant/disputes', 22)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 6, permission_id FROM ops_permission WHERE perm_code = 'merchant:disputes:reply'
ON CONFLICT DO NOTHING;
