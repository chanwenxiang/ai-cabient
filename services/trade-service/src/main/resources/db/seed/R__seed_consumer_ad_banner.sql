-- R__seed_consumer_ad_banner.sql
-- =====================================================================
-- 墓碑迁移：消费者首页「演示轮播投放」种子已作废（2026-10-21 用户拍板「那个不要了」，
-- 原因：真机柜屏上留下两个空白块，运营误判成故障）。
--
-- 🔴 为什么文件还在，而不是直接删掉：
--   本迁移在各环境 `flyway_schema_history` 里留有应用记录（本机实测 installed_rank=322、
--   checksum=266164640、2026-10-08 应用）。**删文件 = 记录指向不存在的脚本** ⇒ Flyway
--   validate 抛 `Detected applied migration not resolved locally: seed consumer ad banner`
--   ⇒ trade-service 整个上下文启动失败（2026-10-21 18:03 重建后真实发生，容器崩环 ~4 分钟）。
--   同口径先例：V134/V135 当年也是「内容改成无害」而非删文件（见
--   docs/COMPETITOR_BENCHMARK_AND_ROADMAP_2026-09-17.md 第 ⑤/⑥ 段）。
--   ⇒ **不要顺手把这个文件删了**，那会让任何跑过旧版种子的环境上线即挂。
--
-- 幂等：三条 DELETE 无副作用，重复执行不报错（R__ 改了 checksum 就会重跑，必须幂等）。
-- 生产保护：沿用原种子的 '${seed_env}' 守卫 —— 当初 INSERT 只在 local/dev/uat 生效，
--   生产从未写入这些行，因此生产也无需清理。
--
-- ⚠️ 本机（dev）已于 2026-10-21 18:06 手工删除过那条历史记录以恢复启动：
--   `DELETE FROM flyway_schema_history WHERE installed_rank=322 AND description='seed consumer ad banner';`
--   ⇒ 本文件在 dev 会被当作**新的可重复迁移**执行一次，结果与上面三张表的现状一致（均为 0 行）。
--   其它未手工处理的环境不需要再动台账：直接部署本文件即可（校验通过 + 幂等清理）。
-- =====================================================================

-- ---------- 1. 先解关联（ad_campaign_item 同时外引用 campaign 与 asset） ----------
DELETE FROM ad_campaign_item i
USING ad_campaign c
WHERE i.campaign_id = c.campaign_id
  AND c.name = '演示轮播投放'
  AND '${seed_env}' IN ('local','dev','uat');

-- ---------- 2. 两条演示素材（仍被别的投放引用时保留） ----------
-- 🔴 子句顺序刻意如此：门禁 check:flyway-seed-separation 在剥注释后要求出现字面
--   `WHERE NOT EXISTS`（幂等判据），别把它「顺手整理」成 AND NOT EXISTS。
DELETE FROM media_asset m
WHERE NOT EXISTS (SELECT 1 FROM ad_campaign_item i WHERE i.asset_id = m.asset_id)
  AND m.title IN ('轮播测试图一', '轮播测试图二')
  AND m.storage_uri LIKE 'minio://cabinet-videos/ad/test-banner-%'
  AND '${seed_env}' IN ('local','dev','uat');

-- ---------- 3. 演示投放计划本体 ----------
DELETE FROM ad_campaign c
WHERE c.name = '演示轮播投放'
  AND NOT EXISTS (SELECT 1 FROM ad_campaign_item i WHERE i.campaign_id = c.campaign_id)
  AND '${seed_env}' IN ('local','dev','uat');
