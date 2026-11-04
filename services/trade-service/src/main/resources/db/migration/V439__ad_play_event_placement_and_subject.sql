-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- TABLES: ad_play_event (+3 可空列 activity_id/position/user_id，+3 索引)
-- LOCK_RISK: low（ADD COLUMN 无默认值 ⇒ PG 11+ 元数据操作，不重写表；当前实树 dev 库该行数为 0）
-- ROLLBACK: ALTER TABLE ad_play_event DROP COLUMN activity_id, DROP COLUMN position, DROP COLUMN user_id;
--           DROP INDEX idx_ad_play_user, idx_ad_play_position, idx_ad_play_activity
-- NOTES: 批次 S（MA-151 弹窗位无打点 + MP 主体列归位）。
--        为什么要三列而不是把 activityId 塞进 campaign_id：banner 报的是 AdCampaign 的 id
--        （MarketingController /ads/{campaignId}/events），而开门前/关门后弹窗带的是
--        PromotionActivity 的 id（MarketingPopupDto.activityId）——两张表的 id 混进同一列
--        就是此前 `MP-U{userId}` 塞 device_id 的同一类错误，事后无法拆开。
--        position 取值 = 字典 promotion_placement_position（BANNER / POPUP_BEFORE_OPEN / POPUP_AFTER_CLOSE）；
--        设备侧经 /internal/v1/devices/*/ad-play 上报的行**不属于展示位**，position 保持 NULL（不臆造取值）。
--        回填：历史小程序行的主体只存在于 device_id 前缀里 ⇒ 用正则守卫把纯数字的 MP-U{n} 归位到 user_id，
--        device_id 原样保留（不改历史报表口径）；非 MP-U 前缀的行（柜机号）一律不动。

ALTER TABLE ad_play_event ADD COLUMN IF NOT EXISTS activity_id BIGINT NULL;
ALTER TABLE ad_play_event ADD COLUMN IF NOT EXISTS position VARCHAR(32) NULL;
ALTER TABLE ad_play_event ADD COLUMN IF NOT EXISTS user_id BIGINT NULL;

CREATE INDEX IF NOT EXISTS idx_ad_play_activity ON ad_play_event (activity_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ad_play_position ON ad_play_event (position, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ad_play_user ON ad_play_event (user_id, created_at DESC);

-- ── 历史行归位（守卫式：只有 device_id 形如 MP-U<纯数字> 才回填，且只写一次）─────────
UPDATE ad_play_event
SET user_id = SUBSTRING(device_id FROM 4)::BIGINT
WHERE device_id ~ '^MP-U[0-9]+$'
  AND user_id IS NULL;

-- 历史小程序行全部来自轮播位打点（当时只有 marketing-ad-banner 在报）⇒ position 补 BANNER
UPDATE ad_play_event
SET position = 'BANNER'
WHERE device_id LIKE 'MP-U%'
  AND position IS NULL;
