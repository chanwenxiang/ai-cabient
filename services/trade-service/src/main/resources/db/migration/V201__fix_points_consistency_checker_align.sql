-- V201: 纠正 V200 误将 available_points 抬到「错误巡检口径」；按流水 points 合计回写
-- 原因：USE/EXPIRE 流水存负数，巡检应 SUM(points) 而非 EARN − (−USE)

UPDATE member m
SET available_points = sub.calc,
    updated_at = NOW()
FROM (
    SELECT l.member_id,
           COALESCE(SUM(l.points), 0)::INT AS calc
    FROM member_points_log l
    GROUP BY l.member_id
) sub
WHERE m.member_id = sub.member_id
  AND m.available_points <> sub.calc;

-- 若 V200 误标 FIXED 的积分项实际已一致，关闭残留 FAIL（巡检修复后会自动 resolve）
UPDATE data_consistency_record
SET status = 'FIXED',
    fixed_at = NOW(),
    error_message = COALESCE(error_message, '') || ' | V201 points checker align'
WHERE status = 'FAIL'
  AND check_type = 'POINTS_BALANCE';
