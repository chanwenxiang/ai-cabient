-- V144: 商品主数据编号 sku_code + brand/spec/unit；条码有值唯一；菜单拆分

ALTER TABLE sku_catalog
    ADD COLUMN IF NOT EXISTS sku_code BIGINT,
    ADD COLUMN IF NOT EXISTS brand VARCHAR(64),
    ADD COLUMN IF NOT EXISTS spec VARCHAR(128),
    ADD COLUMN IF NOT EXISTS unit VARCHAR(16) NOT NULL DEFAULT '件';

-- 存量按创建时间、sku_id 赋号（从 100001 起）
WITH ordered AS (
    SELECT sku_id,
           100000 + ROW_NUMBER() OVER (ORDER BY created_at NULLS LAST, sku_id) AS code
    FROM sku_catalog
    WHERE sku_code IS NULL
)
UPDATE sku_catalog s
SET sku_code = o.code
FROM ordered o
WHERE s.sku_id = o.sku_id;

ALTER TABLE sku_catalog
    ALTER COLUMN sku_code SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_sku_catalog_sku_code ON sku_catalog (sku_code);

CREATE SEQUENCE IF NOT EXISTS sku_catalog_sku_code_seq;

SELECT setval(
    'sku_catalog_sku_code_seq',
    GREATEST(COALESCE((SELECT MAX(sku_code) FROM sku_catalog), 100000), 100000)
);

ALTER TABLE sku_catalog
    ALTER COLUMN sku_code SET DEFAULT nextval('sku_catalog_sku_code_seq');

-- 空串条码规范为 NULL，再建部分唯一索引
UPDATE sku_catalog SET barcode = NULL WHERE barcode IS NOT NULL AND btrim(barcode) = '';

DROP INDEX IF EXISTS idx_sku_catalog_barcode;
CREATE UNIQUE INDEX IF NOT EXISTS uk_sku_catalog_barcode_not_null
    ON sku_catalog (barcode)
    WHERE barcode IS NOT NULL;

-- 菜单：商品管理 + 识别入驻
UPDATE ops_permission
SET perm_name = '商品管理', path = '/skus', parent_id = 470, sort_order = 50, status = 'ACTIVE'
WHERE perm_code = 'ops:sku:list';

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order, status)
VALUES (562, 470, 'ops:sku-vision:list', '识别入驻', 'C', '/sku-vision', 52, 'ACTIVE')
ON CONFLICT (perm_code) DO UPDATE SET
  parent_id = EXCLUDED.parent_id,
  perm_name = EXCLUDED.perm_name,
  perm_type = 'C',
  path = EXCLUDED.path,
  sort_order = EXCLUDED.sort_order,
  status = 'ACTIVE';

-- 识别演示紧挨识别入驻之后
UPDATE ops_permission
SET parent_id = 470, sort_order = 54, path = '/recognition-demo', status = 'ACTIVE'
WHERE perm_code = 'ops:recognition-demo:view';

UPDATE ops_permission SET parent_id = 470, sort_order = 60, path = '/vision-mappings', status = 'ACTIVE'
WHERE perm_code = 'ops:vision:list';
UPDATE ops_permission SET parent_id = 470, sort_order = 70, path = '/upload-queue', status = 'ACTIVE'
WHERE perm_code = 'ops:session:upload';

-- 有商品列表权限的角色同步获得识别入驻菜单
INSERT INTO ops_role_permission (role_id, permission_id)
SELECT DISTINCT rp.role_id, p_new.permission_id
FROM ops_permission p_gate
JOIN ops_role_permission rp ON rp.permission_id = p_gate.permission_id
JOIN ops_permission p_new ON p_new.perm_code = 'ops:sku-vision:list'
WHERE p_gate.perm_code IN ('ops:sku:list', 'ops:admin')
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT 1, permission_id FROM ops_permission WHERE perm_code = 'ops:sku-vision:list'
ON CONFLICT DO NOTHING;

SELECT setval(
    pg_get_serial_sequence('ops_permission', 'permission_id'),
    GREATEST((SELECT COALESCE(MAX(permission_id), 1) FROM ops_permission), 1)
);
