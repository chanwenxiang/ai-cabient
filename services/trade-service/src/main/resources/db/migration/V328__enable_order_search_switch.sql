-- MIGRATION_KIND: backfill
-- MIGRATION_REVIEWED: yes
-- TABLES: system_config（开 1 个开关：订单列表搜索框）
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = UPDATE system_config SET config_value='false'
--           WHERE config_key = 'consumer.order_search.enabled'
--           （或运营台「系统配置」页运行时改，无需再出迁移）
-- NOTES: 2026-10-08 B4 拍板（用户授权「按推荐来」）：
--   开 `consumer.order_search.enabled`（V315 引入，默认 false）——
--   B4 清单里**唯一不依赖外部输入**的开关：
--   · ad_banner/wx_ad 已开（V316）但渲染占位，等 B5 流量主 unit_id
--   · pay_channel_select 等 C2 商户资质
--   · coupon/product_detail/charts 维持关（无上游数据源）
--   开的是消费端订单列表的搜索框可见性，纯 UI 能力，不动任何资金/数据口径。
--   「关闭后能再开」链路：system_config 运行时可改（运营台系统配置页 + 审计历史），
--   无需回滚迁移（forward-only）。

UPDATE system_config
SET config_value = 'true',
    description  = '消费端订单列表搜索框（2026-10-08 B4 拍板打开；运营台可运行时关）'
WHERE config_key = 'consumer.order_search.enabled';
