-- IMP-020：修复演示设备 100001 名称乱码（????????）
-- 用 hex UTF-8 写入，避免 Windows Flyway 客户端编码误伤

UPDATE device_info
SET device_name = convert_from(decode('e6bc94e7a4bae69f9c2d313030303031', 'hex'), 'UTF8')
WHERE device_id = '100001'
  AND (
    device_name IS NULL
    OR btrim(device_name) = ''
    OR device_name ~ '\?{3,}'
    OR device_name LIKE '%' || CHR(65533) || '%'
  );
