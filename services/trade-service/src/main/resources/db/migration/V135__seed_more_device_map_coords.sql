-- 为所有缺坐标的设备补演示经纬度（在已有 V134 基础上扩展 CAB-OTHER 等）
UPDATE device_info
SET latitude = 31.2304,
    longitude = 121.4737,
    address = COALESCE(NULLIF(TRIM(address), ''), '上海市黄浦区演示点位 A'),
    route_code = COALESCE(NULLIF(TRIM(route_code), ''), 'R-DEMO-01')
WHERE device_id = 'CAB-001'
  AND (latitude IS NULL OR longitude IS NULL OR route_code IS NULL OR TRIM(route_code) = '');

UPDATE device_info
SET latitude = COALESCE(latitude, 31.2450),
    longitude = COALESCE(longitude, 121.5050),
    address = COALESCE(NULLIF(TRIM(address), ''), '上海市浦东新区演示点位 B'),
    route_code = COALESCE(NULLIF(TRIM(route_code), ''), 'R-DEMO-02')
WHERE device_id IN ('CAB-OTHER', 'CAB-002', 'CAB-003')
  AND (latitude IS NULL OR longitude IS NULL);

-- 其余仍无坐标的设备：按行号偏移散点
WITH ranked AS (
    SELECT device_id,
           ROW_NUMBER() OVER (ORDER BY device_id) AS rn
    FROM device_info
    WHERE latitude IS NULL OR longitude IS NULL
)
UPDATE device_info d
SET latitude = 31.20 + (r.rn * 0.012),
    longitude = 121.45 + (r.rn * 0.015),
    address = COALESCE(NULLIF(TRIM(d.address), ''), '演示散落点位 ' || d.device_id),
    route_code = COALESCE(NULLIF(TRIM(d.route_code), ''), 'R-DEMO-X')
FROM ranked r
WHERE d.device_id = r.device_id;
