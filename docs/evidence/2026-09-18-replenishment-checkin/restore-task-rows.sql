-- 还原 replenishment_task 的存量行（由 task_rows.BEFORE.tsv 生成）。
-- 用途：运行时负向验证里，修复前的代码会把 COMPLETED/CANCELLED 任务签到成
--       IN_PROGRESS 并覆盖 check_in_at/lat/lng，跑完必须还原，否则开发库带着脏数据。
-- 生成方式：python 脚本从 task_rows.BEFORE.tsv 逐列拼出（见 README.md）。
BEGIN;
UPDATE replenishment_task SET route_id = 1, device_id = '777740024057', assignee_user_id = 100000030, status = 'COMPLETED', notes = 'seq=1 dist=0m', completed_at = '2026-09-13 03:02:28.394615+00', created_at = '2026-09-13 03:02:27.183564+00', outbound_id = 1, check_in_at = '2026-09-13 03:02:27.437109+00', check_in_lat = NULL, check_in_lng = NULL, request_id = NULL WHERE task_id = 1;
UPDATE replenishment_task SET route_id = 2, device_id = '777740024057', assignee_user_id = NULL, status = 'CANCELLED', notes = 'merchant request 1', completed_at = NULL, created_at = '2026-09-13 03:06:18.636364+00', outbound_id = NULL, check_in_at = '2026-09-18 04:44:53.294196+00', check_in_lat = '31.238', check_in_lng = '121.48', request_id = 1 WHERE task_id = 2;
COMMIT;
