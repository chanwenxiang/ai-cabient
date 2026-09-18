-- 回滚 _backfill_device_coords.sql：把这三台柜机恢复为「无坐标」原状。
-- 依据：回填前三行的 latitude / longitude / address / route_code 均为 NULL（2026-09-18 实查）。
-- 注意：仅回滚本脚本填过的行；若期间有人经后台编辑页补录了真实坐标，请勿执行。

BEGIN;

UPDATE device_info
SET latitude = NULL, longitude = NULL, address = NULL, route_code = NULL, updated_at = now()
WHERE device_id IN ('CAB-001', '777740024057', '330449777078');

COMMIT;
