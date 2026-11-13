-- OBS-010：清理演示脚本写入导致的 ???? 乱码名称（非产品缺陷）
-- MIGRATION_IMMUTABLE_EXEMPT: S14 修复批——ad_campaign（V155 DROP）与 member_points_log（V136 DROP）
--   的语句为死语句（任何应用了对应 DROP 的库必失败），移除后全新空库可按序构建；测试库重建消化 checksum。

UPDATE replenishment_route
SET route_name = CASE route_id
    WHEN 9 THEN 'R4演示补货路线'
    WHEN 10 THEN 'R5演示补货路线'
    ELSE '演示补货路线-' || route_id::text
  END
WHERE (route_name LIKE '%????%' OR route_name ~ '\?{2,}');

-- OBS-018：演示消费者 10001 积分补齐——原 INSERT 依赖 member_points_log（V136 已 DROP），死语句移除。

UPDATE member
SET available_points = GREATEST(COALESCE(available_points, 0), 120),
    total_points = GREATEST(COALESCE(total_points, 0), 120)
WHERE user_id = 10001;
