-- R__seed_consumer_ad_banner.sql
-- =====================================================================
-- 消费者小程序首页推广位（S1）的**演示投放**：2 张轮播素材。
--
-- 目的（2026-10-08 用户要求「放俩个测试一下」）：
--   验证 `device-ad-banner.vue` 的 <swiper> 轮播真机能跑起来。
--   此前该位置只能显示单张占位图 —— 库里那条「演示屏保投放」是**僵尸数据**：
--   device_scope='SPECIFIC' 但 ad_campaign_device 里 0 行绑定，
--   `AdCampaignService:208-212` 的 noneMatch 判定直接 continue ⇒ 永不命中。
--
-- 🔴 为什么 channel 选 'CABINET_SCREEN' 而不是 'MINI_PROGRAM'（反直觉，务必别改）：
--   `AdCampaignService.screenContent():205` 显式 `continue` 掉 channel='MINI_PROGRAM'
--   的计划（注释「小程序渠道不上柜机屏（V296/P3-6）」），且 `AdCampaignServiceTest:224`
--   把这个行为锁成了单测。本文件走的是 device-ad-banner（柜机屏）这条链路，
--   所以**必须** CABINET_SCREEN；设 MINI_PROGRAM 会让 items 直接为空。
--   ⚠️ docs/AD_MONETIZATION_DESIGN.md §13「换素材时必须注意的两个约束」第 1 条
--   写的是「channel 必须选 MINI_PROGRAM」—— 那是给**营销页** marketing-ad-banner
--   （listMiniProgramBanners）写的，与本链路 channel 语义相反。文档该条属已知 bug。
--
-- 🔴 为什么 device_scope 用 'ALL' 而不是 SPECIFIC：
--   演示柜机 device_id 是**运行时随机生成的 12 位数字**（DeviceIdService:51-67，
--   硬编码 CAB-001 已被 V287 删除），写死会因外键
--   ad_campaign_device_device_id_fkey 直接报错。'ALL' 免掉这个依赖。
--
-- 🔴 storage_uri 必须是真实 MinIO URI（minio://<bucket>/<key>）：
--   playUrl 由后端硬编码为 /api/v2/media/ad-assets/{assetId}（AdCampaignService:220），
--   该端点走 MinioVideoService.streamTo():300-311，非 minio://oss:// s3:// 前缀一律 400
--   ⇒ 填相对路径（如 /static/x.png）虽能写库，但图片 100% 加载失败、swiper 显示破图。
--   对应对象已上传：bucket=cabinet-videos，key=ad/test-banner-01.png / test-banner-02.png
--
-- 幂等：全部 WHERE NOT EXISTS / ON CONFLICT，可重复迁移重跑不产生重复行。
-- 生产保护：写语句包在 '${seed_env}' IN ('local','dev','uat') 守卫里
--   （application-prod.yml 固定 seed_env=none ⇒ 生产跳过）。
-- =====================================================================

-- ---------- 1. 素材：两条 IMAGE（轮播需要 ≥2 条才能看出自动播放） ----------
INSERT INTO media_asset (title, asset_type, storage_uri, duration_seconds, status, uploaded_by)
SELECT v.title, 'IMAGE', v.storage_uri, 8, 'ACTIVE', 100000001
FROM (
    VALUES
        ('轮播测试图一', 'minio://cabinet-videos/ad/test-banner-01.png'),
        ('轮播测试图二', 'minio://cabinet-videos/ad/test-banner-02.png')
) AS v(title, storage_uri)
WHERE NOT EXISTS (
    SELECT 1 FROM media_asset m WHERE m.title = v.title AND m.storage_uri = v.storage_uri
)
AND '${seed_env}' IN ('local','dev','uat');

-- ---------- 2. 投放计划：RUNNING + 全设备 + 柜机屏渠道 ----------
INSERT INTO ad_campaign (name, status, channel, device_scope, start_at, end_at, created_by)
SELECT '演示轮播投放', 'RUNNING', 'CABINET_SCREEN', 'ALL',
       NOW() - INTERVAL '1 day', NOW() + INTERVAL '90 days', 100000001
WHERE NOT EXISTS (
    SELECT 1 FROM ad_campaign c WHERE c.name = '演示轮播投放'
)
AND '${seed_env}' IN ('local','dev','uat');

-- ---------- 3. 关联两条素材并定序（sort_order 决定轮播先后） ----------
INSERT INTO ad_campaign_item (campaign_id, asset_id, sort_order)
SELECT c.campaign_id, m.asset_id, v.sort_order
FROM ad_campaign c
CROSS JOIN media_asset m
CROSS JOIN (
    VALUES
        ('轮播测试图一', 1),
        ('轮播测试图二', 2)
) AS v(title, sort_order)
WHERE c.name = '演示轮播投放'
  AND m.title = v.title
  AND m.storage_uri LIKE 'minio://cabinet-videos/ad/test-banner-%'
  AND NOT EXISTS (
      SELECT 1 FROM ad_campaign_item i
      WHERE i.campaign_id = c.campaign_id AND i.asset_id = m.asset_id
  )
AND '${seed_env}' IN ('local','dev','uat');
