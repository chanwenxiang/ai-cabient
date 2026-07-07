-- 从旧库 ego-automat (MySQL) 导出设备
-- machine_code 建议直接作为新系统 device_id

SELECT
    m.machine_id,
    m.machine_code,
    COALESCE(m.machine_name, m.machine_code) AS machine_name,
    CASE WHEN m.online = 1 THEN 'ONLINE' ELSE 'OFFLINE' END AS online_status
FROM ego_machine_base_info m
WHERE m.machine_code IS NOT NULL
  AND m.machine_code != ''
ORDER BY m.machine_id;
