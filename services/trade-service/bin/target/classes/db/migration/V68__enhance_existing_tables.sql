-- P1: 现有表缺少的字段补充

-- shopping_session 补充
ALTER TABLE shopping_session
    ADD COLUMN IF NOT EXISTS device_temp_celsius DECIMAL(4,1),
    ADD COLUMN IF NOT EXISTS network_rssi INT,
    ADD COLUMN IF NOT EXISTS scan_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS scan_device_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS dispute_flag BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS coupon_id BIGINT,
    ADD COLUMN IF NOT EXISTS coupon_discount_cents INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS coupon_code VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_session_scan_time ON shopping_session (scan_time) WHERE scan_time IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_session_dispute_flag ON shopping_session (dispute_flag) WHERE dispute_flag = TRUE;

-- cabinet_order 补充
ALTER TABLE cabinet_order
    ADD COLUMN IF NOT EXISTS pay_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS refund_time TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS pay_channel VARCHAR(16),
    ADD COLUMN IF NOT EXISTS remark VARCHAR(512),
    ADD COLUMN IF NOT EXISTS pay_score_used BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS refund_reason VARCHAR(256);

-- device_info 补充
ALTER TABLE device_info
    ADD COLUMN IF NOT EXISTS install_address VARCHAR(256),
    ADD COLUMN IF NOT EXISTS install_lat DECIMAL(10,7),
    ADD COLUMN IF NOT EXISTS install_lng DECIMAL(10,7),
    ADD COLUMN IF NOT EXISTS merchant_contact VARCHAR(64),
    ADD COLUMN IF NOT EXISTS merchant_contact_phone VARCHAR(32),
    ADD COLUMN IF NOT EXISTS last_maintain_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS total_open_count INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS total_sales_cents BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS cabinet_type VARCHAR(16) NOT NULL DEFAULT 'VISION',
    ADD COLUMN IF NOT EXISTS camera_config JSONB,
    ADD COLUMN IF NOT EXISTS last_heartbeat_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cpu_usage DECIMAL(5,2),
    ADD COLUMN IF NOT EXISTS memory_usage DECIMAL(5,2),
    ADD COLUMN IF NOT EXISTS disk_usage DECIMAL(5,2),
    ADD COLUMN IF NOT EXISTS storage_total_bytes BIGINT,
    ADD COLUMN IF NOT EXISTS storage_used_bytes BIGINT;

CREATE INDEX IF NOT EXISTS idx_device_merchant ON device_info (merchant_id);
CREATE INDEX IF NOT EXISTS idx_device_type ON device_info (cabinet_type);
CREATE INDEX IF NOT EXISTS idx_device_online_heartbeat ON device_info (online_status, last_heartbeat_at);

-- sku_catalog 补充
ALTER TABLE sku_catalog
    ADD COLUMN IF NOT EXISTS category_id VARCHAR(32),
    ADD COLUMN IF NOT EXISTS image_url VARCHAR(512),
    ADD COLUMN IF NOT EXISTS barcode VARCHAR(64),
    ADD COLUMN IF NOT EXISTS supplier_id VARCHAR(32) REFERENCES supplier(supplier_id),
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS sales_volume INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS is_perishable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS storage_temp_min DECIMAL(4,1),
    ADD COLUMN IF NOT EXISTS storage_temp_max DECIMAL(4,1),
    ADD COLUMN IF NOT EXISTS sku_description TEXT,
    ADD COLUMN IF NOT EXISTS category_path VARCHAR(256),
    ADD COLUMN IF NOT EXISTS update_version INT NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_sku_catalog_status ON sku_catalog (status) WHERE status = 'ACTIVE';
CREATE INDEX IF NOT EXISTS idx_sku_catalog_category ON sku_catalog (category_id);
CREATE INDEX IF NOT EXISTS idx_sku_catalog_barcode ON sku_catalog (barcode) WHERE barcode IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_sku_catalog_supplier ON sku_catalog (supplier_id);

-- merchant 表补充
ALTER TABLE merchant
    ADD COLUMN IF NOT EXISTS contact_name VARCHAR(64),
    ADD COLUMN IF NOT EXISTS contact_phone VARCHAR(32),
    ADD COLUMN IF NOT EXISTS legal_person VARCHAR(64),
    ADD COLUMN IF NOT EXISTS business_license_url VARCHAR(512),
    ADD COLUMN IF NOT EXISTS bank_account_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS bank_account_no VARCHAR(64),
    ADD COLUMN IF NOT EXISTS bank_name VARCHAR(128),
    ADD COLUMN IF NOT EXISTS settlement_cycle VARCHAR(16) NOT NULL DEFAULT 'DAILY',
    ADD COLUMN IF NOT EXISTS settlement_day_offset INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS min_settle_amount_cents INT NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS contract_start_at DATE,
    ADD COLUMN IF NOT EXISTS contract_end_at DATE,
    ADD COLUMN IF NOT EXISTS address VARCHAR(256),
    ADD COLUMN IF NOT EXISTS province VARCHAR(64),
    ADD COLUMN IF NOT EXISTS city VARCHAR(64),
    ADD COLUMN IF NOT EXISTS district VARCHAR(64),
    ADD COLUMN IF NOT EXISTS audit_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS auditor_id BIGINT,
    ADD COLUMN IF NOT EXISTS audit_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS audit_remark TEXT;

CREATE INDEX IF NOT EXISTS idx_merchant_audit ON merchant (audit_status) WHERE audit_status = 'PENDING';

-- 附件统一表
CREATE TABLE IF NOT EXISTS file_attachment (
    file_id         BIGSERIAL       PRIMARY KEY,
    ref_type        VARCHAR(32)     NOT NULL,
    ref_id          VARCHAR(64)     NOT NULL,
    file_name       VARCHAR(256)    NOT NULL,
    file_size       BIGINT,
    content_type    VARCHAR(128),
    storage_path    VARCHAR(512)    NOT NULL,
    storage_bucket  VARCHAR(64),
    uploaded_by     BIGINT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_file_ref ON file_attachment (ref_type, ref_id);

-- 通用操作日志（对标 RuoYi sys_oper_log）
CREATE TABLE IF NOT EXISTS sys_oper_log (
    oper_id         BIGSERIAL       PRIMARY KEY,
    oper_type       VARCHAR(32)     NOT NULL,
    title           VARCHAR(128),
    operator_id     BIGINT,
    operator_name   VARCHAR(64),
    target_type     VARCHAR(32),
    target_id       VARCHAR(64),
    method          VARCHAR(256),
    request_url     VARCHAR(512),
    request_params  TEXT,
    request_body    TEXT,
    response_body   TEXT,
    status          SMALLINT        NOT NULL DEFAULT 0,  -- 0=成功 1=失败
    error_msg       TEXT,
    cost_ms         INT,
    ip_address      VARCHAR(64),
    user_agent      VARCHAR(512),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_oper_log_type ON sys_oper_log (oper_type, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_oper_log_operator ON sys_oper_log (operator_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_oper_log_target ON sys_oper_log (target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_oper_log_created ON sys_oper_log (created_at DESC);

-- 权限：操作日志查看
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order) VALUES
    (290, 1, 'ops:operlog',           '操作日志',   'C', '/admin/oper-logs',  23),
    (291, 290, 'ops:operlog:export',  '日志导出',   'F', NULL,                1)
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission WHERE perm_code LIKE 'ops:operlog%'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 5, permission_id FROM ops_permission WHERE perm_code = 'ops:operlog'
ON CONFLICT DO NOTHING;
