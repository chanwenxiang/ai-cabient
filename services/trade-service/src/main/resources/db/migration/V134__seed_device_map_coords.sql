-- 演示柜补充经纬度，便于投放地图多点展示
UPDATE device_info
SET latitude = 31.2304,
    longitude = 121.4737,
    address = COALESCE(NULLIF(TRIM(address), ''), '上海市黄浦区演示点位 A')
WHERE device_id = 'CAB-001'
  AND (latitude IS NULL OR longitude IS NULL);

UPDATE device_info
SET latitude = 31.2450,
    longitude = 121.5050,
    address = COALESCE(NULLIF(TRIM(address), ''), '上海市浦东新区演示点位 B'),
    route_code = COALESCE(NULLIF(TRIM(route_code), ''), 'R-DEMO-01')
WHERE device_id = 'CAB-002'
  AND (latitude IS NULL OR longitude IS NULL);

UPDATE device_info
SET latitude = 31.2100,
    longitude = 121.4500,
    address = COALESCE(NULLIF(TRIM(address), ''), '上海市徐汇区演示点位 C'),
    route_code = COALESCE(NULLIF(TRIM(route_code), ''), 'R-DEMO-02')
WHERE device_id = 'CAB-003'
  AND (latitude IS NULL OR longitude IS NULL);

UPDATE device_info
SET route_code = COALESCE(NULLIF(TRIM(route_code), ''), 'R-DEMO-01')
WHERE device_id = 'CAB-001'
  AND (route_code IS NULL OR TRIM(route_code) = '');
