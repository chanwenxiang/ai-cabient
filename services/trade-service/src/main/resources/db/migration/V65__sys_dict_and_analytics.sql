-- sys_dict schema + analytics / dict permissions (seed data loaded by SysDictBootstrap)
CREATE TABLE IF NOT EXISTS sys_dict_type (
    dict_type   VARCHAR(64)  PRIMARY KEY,
    dict_name   VARCHAR(128) NOT NULL,
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    remark      VARCHAR(256),
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS sys_dict_data (
    dict_data_id BIGSERIAL PRIMARY KEY,
    dict_type    VARCHAR(64)  NOT NULL REFERENCES sys_dict_type(dict_type) ON DELETE CASCADE,
    dict_value   VARCHAR(64)  NOT NULL,
    dict_label   VARCHAR(128) NOT NULL,
    sort_order   INT          NOT NULL DEFAULT 0,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    remark       VARCHAR(256),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    UNIQUE (dict_type, dict_value)
);

CREATE INDEX IF NOT EXISTS idx_sys_dict_data_type ON sys_dict_data (dict_type);

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (230, 1, 'ops:dict',        '字典管理', 'M', NULL,              17),
    (231, 230, 'ops:dict:list', '字典查看', 'C', '/admin/dicts',    1),
    (232, 230, 'ops:dict:edit', '字典编辑', 'F', NULL,              2),
    (11,  1, 'ops:analytics:view', '数据分析', 'C', '/admin/analytics', 0)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission
WHERE perm_code IN ('ops:dict', 'ops:dict:list', 'ops:dict:edit', 'ops:analytics:view')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 2, permission_id FROM ops_permission
WHERE perm_code IN ('ops:dict', 'ops:dict:list', 'ops:analytics:view')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 5, permission_id FROM ops_permission
WHERE perm_code IN ('ops:dict', 'ops:dict:list', 'ops:analytics:view')
ON CONFLICT DO NOTHING;
