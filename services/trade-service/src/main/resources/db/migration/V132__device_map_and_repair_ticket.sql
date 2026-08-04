-- V131: 投放地图权限 + 维修工单

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 490, 470, 'ops:device-map:view', '投放地图', 'C', '/device-map', 25, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:device-map:view');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 491, 470, 'ops:repair:list', '维修工单', 'C', '/repair-tickets', 35, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:repair:list');

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
SELECT 492,
       (SELECT permission_id FROM ops_permission WHERE perm_code = 'ops:repair:list' LIMIT 1),
       'ops:repair:edit', '维修工单编辑', 'F', NULL, 10, 'ACTIVE'
WHERE NOT EXISTS (SELECT 1 FROM ops_permission WHERE perm_code = 'ops:repair:edit');

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code IN ('ops:device-map:view', 'ops:repair:list', 'ops:repair:edit')
WHERE p_gate.perm_code IN ('ops:device:list', 'ops:device-ops:list', 'ops:admin')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:device-map:view', 'ops:repair:list', 'ops:repair:edit')
ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS repair_ticket (
    ticket_id      BIGSERIAL PRIMARY KEY,
    device_id      VARCHAR(64)  NOT NULL,
    title          VARCHAR(128) NOT NULL,
    fault_type     VARCHAR(64),
    status         VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    assignee       VARCHAR(64),
    priority       VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    remark         VARCHAR(512),
    created_by     BIGINT,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    closed_at      TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_repair_ticket_device ON repair_ticket (device_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_repair_ticket_status ON repair_ticket (status, created_at DESC);

COMMENT ON TABLE repair_ticket IS '设备维修工单';
COMMENT ON COLUMN repair_ticket.status IS 'OPEN|IN_PROGRESS|DONE|CANCELLED';
COMMENT ON COLUMN repair_ticket.priority IS 'LOW|NORMAL|HIGH|URGENT';

CREATE TABLE IF NOT EXISTS repair_ticket_event (
    event_id     BIGSERIAL PRIMARY KEY,
    ticket_id    BIGINT       NOT NULL,
    from_status  VARCHAR(32),
    to_status    VARCHAR(32)  NOT NULL,
    action       VARCHAR(32)  NOT NULL,
    operator_id  BIGINT,
    remark       VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_repair_ticket_event_ticket
    ON repair_ticket_event (ticket_id, created_at DESC);

-- 演示柜种子工单（若设备存在）
INSERT INTO repair_ticket (device_id, title, fault_type, status, assignee, priority, remark, created_by)
SELECT 'CAB-001', '门锁偶发卡顿', 'DOOR', 'OPEN', NULL, 'NORMAL', '演示维修工单', 100000001
WHERE EXISTS (SELECT 1 FROM device_info WHERE device_id = 'CAB-001')
  AND NOT EXISTS (SELECT 1 FROM repair_ticket WHERE device_id = 'CAB-001' AND title = '门锁偶发卡顿');

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
