-- MIGRATION_KIND: schema
-- 批次 T：放开 ad_play_event 的 campaign_id / device_id 非空约束
--
-- 为什么必须改：批次 S 让弹窗位（POPUP_BEFORE_OPEN / POPUP_AFTER_CLOSE）也写这张表，
-- 但弹窗位主体是 PromotionActivity（只有 activity_id），banner 位主体是 AdCampaign（只有 campaign_id），
-- 小程序上报更没有 device_id。V191 当年是「设备 × 广告计划」单主体模型，两列都 NOT NULL
-- ⇒ 批次 S 的 /placement-events 在真库上**每条都插入失败**，而小程序端 .catch 只记 warn
-- ⇒ 表现为「功能上线了但表一直是空的」。单测拿的是 mock mapper，永远跑不到这条 INSERT，
--    是 PlacementEffectSqlIT 第一次真跑才暴露的（DataIntegrityViolationException）。
--
-- 放开后用什么兜住脏数据：CHECK 保证「行必须挂得上一个主体」——
-- campaign_id（广告计划位）或 activity_id（活动弹窗位）至少一个；device_id 不再有非空要求，
-- 但主体缺失的行从此进不来。
--
-- LOCK_RISK: low（DROP NOT NULL 与新增 CHECK 都是目录操作；CHECK 建约束会扫一次表，dev 实测 0 行）
-- ROLLBACK: ALTER TABLE ad_play_event DROP CONSTRAINT ck_ad_play_subject;
--           ALTER TABLE ad_play_event ALTER COLUMN campaign_id SET NOT NULL;
--           ALTER TABLE ad_play_event ALTER COLUMN device_id SET NOT NULL;
--           （回滚前需先清掉 activity_id-only / device_id 为空的行）
-- NOTES: 已有行不受影响——V191 起就必填 campaign_id，CHECK 的两个分支必然命中；
--        服务器/dev 库若已有历史行也只是「被同一约束验过一遍」，不改写任何数据。

ALTER TABLE ad_play_event ALTER COLUMN campaign_id DROP NOT NULL;
ALTER TABLE ad_play_event ALTER COLUMN device_id DROP NOT NULL;

ALTER TABLE ad_play_event
    ADD CONSTRAINT ck_ad_play_subject CHECK (campaign_id IS NOT NULL OR activity_id IS NOT NULL);
