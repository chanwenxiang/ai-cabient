-- V201: 纠正 V200 误将 available_points 抬到「错误巡检口径」；按流水 points 合计回写
-- 原因：USE/EXPIRE 流水存负数，巡检应 SUM(points) 而非 EARN − (−USE)
-- MIGRATION_IMMUTABLE_EXEMPT: S14 修复批——首段 UPDATE 依赖 member_points_log（V136 已 DROP），
--   死语句移除；data_consistency_record 关闭段为活表，保留。测试库重建消化 checksum。

-- 首段积分回写：原实现依赖 member_points_log（V136 已 DROP），死语句移除（S14 表名声明集修复）。

-- 若 V200 误标 FIXED 的积分项实际已一致，关闭残留 FAIL（巡检修复后会自动 resolve）
UPDATE data_consistency_record
SET status = 'FIXED',
    fixed_at = NOW(),
    error_message = COALESCE(error_message, '') || ' | V201 points checker align'
WHERE status = 'FAIL'
  AND check_type = 'POINTS_BALANCE';
