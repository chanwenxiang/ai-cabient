-- OBS-010：清理演示脚本写入导致的 ???? 乱码名称（非产品缺陷）
UPDATE ad_campaign
SET name = 'R10演示投放计划',
    updated_at = NOW()
WHERE campaign_id = 1
  AND (name LIKE '%????%' OR name ~ '\?{2,}');

UPDATE replenishment_route
SET route_name = CASE route_id
    WHEN 9 THEN 'R4演示补货路线'
    WHEN 10 THEN 'R5演示补货路线'
    ELSE '演示补货路线-' || route_id::text
  END
WHERE (route_name LIKE '%????%' OR route_name ~ '\?{2,}');

-- OBS-018：演示消费者 10001 积分补齐，可兑最低 100 分档（先记流水再抬余额）
INSERT INTO member_points_log (member_id, points, points_type, source_type, source_id, description, created_at, expire_at)
SELECT m.member_id,
       GREATEST(0, 120 - COALESCE(m.available_points, 0)),
       'EARN',
       'DEMO_SEED',
       'obs018-demo-topup',
       '演示积分补齐(可兑最低档)',
       NOW(),
       NOW() + INTERVAL '365 day'
FROM member m
WHERE m.user_id = 10001
  AND COALESCE(m.available_points, 0) < 120
  AND NOT EXISTS (
    SELECT 1 FROM member_points_log l
    WHERE l.member_id = m.member_id
      AND l.source_type = 'DEMO_SEED'
      AND l.source_id = 'obs018-demo-topup'
  );

UPDATE member
SET available_points = GREATEST(COALESCE(available_points, 0), 120),
    total_points = GREATEST(COALESCE(total_points, 0), 120)
WHERE user_id = 10001;
