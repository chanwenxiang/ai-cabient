-- Repair demo cabinet display names corrupted by legacy encoding (e.g. ???-001).
UPDATE device_info
SET device_name = '测试柜-001',
    updated_at  = NOW()
WHERE device_id = 'CAB-001'
  AND (
    device_name IS NULL
    OR device_name = device_id
    OR device_name LIKE '%???%'
  );
