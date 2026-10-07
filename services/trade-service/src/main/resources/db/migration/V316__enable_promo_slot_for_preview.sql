-- MIGRATION_KIND: data
-- MIGRATION_REVIEWED: yes
-- TABLES: system_config（seed 2 条：打开推广位总闸 + 流量主）
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = UPDATE system_config SET config_value='false'
--           WHERE config_key IN ('consumer.ad_banner.enabled','consumer.wx_ad.enabled')
-- NOTES: 2026-10-07 用户决定「把流量主和新品活动都打开，看看样式」。
--
--   ⚠️ **本迁移是「打开展示」，不是「打开资金」** —— 两者严格区分：
--   打开的是**位置可见性**（首页推广位）与**广告渲染**，
--   收入侧（V310 `ad_revenue_daily`）**一行未动**、无需任何数据。
--
--   ---------------------------------------------------------------------------
--   开启后的三种渲染态（由 `promo-slot.ts` 纯函数决定，有单测）
--   ---------------------------------------------------------------------------
--   1) 本柜有生效的自有活动 ⇒ `self`   —— 展示新品/打折活动素材（**腾讯广告让位**）
--   2) 无自有内容 + 下面两个条件都满足 ⇒ `wxAd` —— 微信原生广告组件
--        · consumer.wx_ad.enabled = true
--        · consumer.wx_ad.unit_id 非空（`adunit-` 开头）
--   3) 都没有 ⇒ `placeholder` —— 占位图，**带来源角标**标明「未配置」
--
--   🔴 **流量主尚未开通流量主时**：`unit_id` 仍是空串 ⇒ 第 2态不成立
--   ⇒ 实际落到第 3 态（占位图 + 「未配置」角标）。
--   ⇒ **用户看到的是安全的空位，不是空广告框**。这正是设计里的双重 fail-closed。
--
--   📌 运营看到「有位置但显示占位」时的正确解读：
--   角标写「未配置」= 位置已开但没内容（正常，去建活动/填广告位 ID即可）；
--   角标写「流量主」= 微信广告组件在渲染（说明 unit_id 已填、开关已开）。
--   ⚠️ 角标**不会**写「流量主」而组件却报错 —— 组件内部失败会隐藏自己，
--   此时外层落到占位 + 「未配置」，不会出现「标着广告、实际报错」的误导状态。
--
--   ---------------------------------------------------------------------------
--   为什么不一次把 unit_id 也配上
--   ---------------------------------------------------------------------------
--   `consumer.wx_ad.unit_id` 是**账号资产**（`adunit-` 开头，在微信公众平台
--   「流量主 → 广告位管理」创建）。尚未开通流量主 ⇒ **此时没有任何合法值可填**，
--   留空是唯一正确状态（硬编一个假ID 只会让组件静默失败）。

UPDATE system_config SET config_value = 'true', description = '消费端首页推广位总闸（2026-07-07 打开：用户要看样式）'
WHERE config_key = 'consumer.ad_banner.enabled';

UPDATE system_config SET config_value = 'true', description = '消费端腾讯流量主广告（2026-10-07 打开；unit_id 仍为空 ⇒ 实际渲染占位图）'
WHERE config_key = 'consumer.wx_ad.enabled';
