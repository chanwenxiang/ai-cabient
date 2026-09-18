-- 一次性回填：为「V134/V135 之后新建、因而漏掉坐标回填」的柜机补演示坐标。
-- 背景：V134__seed_device_map_coords / V135__seed_more_device_map_coords 于 2026-08-03 执行，
--       此后经后台新建的柜机（createDevice 原先不写坐标）坐标恒为 NULL。
--       ReplenishmentService.doCheckInTask 已改为「柜机无坐标即拒签」(fail-closed)，
--       不回填则这些柜机的签到会被直接拒掉。
-- 口径：沿用 V134/V135 的演示点位（上海）。
-- 幂等：WHERE 带 (latitude IS NULL OR longitude IS NULL) 守卫，重复执行不影响已填行。
-- 最小改动：只写 latitude/longitude/address，不动 route_code 等其他列。
-- 回滚：见同目录 _rollback_device_coords.sql

BEGIN;

UPDATE device_info
SET latitude   = 31.2304,
    longitude  = 121.4737,
    address    = COALESCE(NULLIF(TRIM(address), ''), '上海市黄浦区演示点位 A'),
    updated_at = now()
WHERE device_id = 'CAB-001'
  AND (latitude IS NULL OR longitude IS NULL);

UPDATE device_info
SET latitude   = 31.2380,
    longitude  = 121.4800,
    address    = COALESCE(NULLIF(TRIM(address), ''), '上海市黄浦区演示点位 D'),
    updated_at = now()
WHERE device_id = '777740024057'
  AND (latitude IS NULL OR longitude IS NULL);

UPDATE device_info
SET latitude   = 31.2620,
    longitude  = 121.5160,
    address    = COALESCE(NULLIF(TRIM(address), ''), '上海市静安区演示点位 E'),
    updated_at = now()
WHERE device_id = '330449777078'
  AND (latitude IS NULL OR longitude IS NULL);

COMMIT;
