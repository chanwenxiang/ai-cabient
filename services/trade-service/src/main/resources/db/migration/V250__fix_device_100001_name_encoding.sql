-- IMP-020: fix garbled demo device_name for 100001 (was ???????)
-- Write via UTF-8 hex so Windows Flyway clients cannot corrupt the payload.

UPDATE device_info
SET device_name = convert_from(decode('e6bc94e7a4bae69f9c2d313030303031', 'hex'), 'UTF8')
WHERE device_id = '100001'
  AND (
    device_name IS NULL
    OR btrim(device_name) = ''
    OR device_name ~ '\?{3,}'
    OR device_name LIKE '%' || CHR(65533) || '%'
  );
