-- MIGRATION_KIND: data
-- MIGRATION_REVIEWED: yes
-- TABLES: system_config（seed 3 条配置，不改表结构）
-- LOCK_RISK: low
-- ROLLBACK: 撤销 = DELETE FROM system_config WHERE config_key IN (本文件列出的 3 个 key)
-- NOTES: 2026-10-07 用户决策落库：B6 设备型号可配置 / B4 开关全关 / B3 自建发布密钥。
--
--   ⚠️ 本迁移**只 seed 数据，不改结构** —— 三项决策都是「配置」而非「表结构」。
--   幂等：全部 ON CONFLICT DO NOTHING。
--
--   ---------------------------------------------------------------------------
--   ① B6：设备型号**不写死**，做成配置（用户 2026-10-07 决策）
--   ---------------------------------------------------------------------------
--   取证：`ChzhLockDriver.kt` 是**唯一**驱动，类名/chzh 写死，
--   **全仓无 `DeviceModel` 枚举** ⇒ 代码层面没有多型号支持，隐含假定「都是 chzh8 系」。
--   而旧系统有 **3 套厂商驱动**（`chzh8` 723 行 / `jinyu2` 2127 行 / `yichu2` 867 行）。
--
--   ⇒ 默认值用 `chzh8`（与代码现有行为**逐字节一致**）：
--   这条 seed 本身**不改变任何运行时行为**，只是把「写死」变成「可配」。
--   ⚠️ 真正的驱动实现（按 config 选驱动）是**独立的代码变更**，不在本迁移。
--
--   型号取值必须与 `EdgeDriverType` 枚举一致（实施时新增）；
--   **未知型号要显式拒绝，不许静默回落到 chzh8** ——
--   静默回落会让「柜机型号配错」表现为「门锁偶尔不响应」这种难查的问题。
--
--   ---------------------------------------------------------------------------
--   ② B4：6 个功能开关**全部保持关闭**（用户 2026-10-07 决策：「如果可以就都关闭」）
--   ---------------------------------------------------------------------------
--   取证：8 个 key 全部**已存在且默认 false** ⇒ **本来就都是关的**，
--   本迁移**只做显式登记**（让运营台能看到「有哪些开关、当前什么值」），不改变取值。
--
--   🔴 双重 fail-closed 已就位（`SystemConfigService:285-291`）：
--   即使误开 `consumer.wx_ad.enabled`，`consumer.wx_ad.unit_id` 为空串
--   ⇒ 前端 `wxAdAvailable = enabled && unitId !== ''` ⇒ **仍不渲染**。
--   ⇒ 「开关开了但忘填广告单元 ID」不会产生「空广告框」。
--
--   `consumer.ad_banner.enabled` 特别说明：它一开就会渲染**占位图**，
--   而占位图对用户无价值、对我们无收益（文档明确「占位不是广告，不上报任何事件」）。
--   ⇒ **保持 false**；只在「决定要上这个位置、但还没投放内容」的过渡期才开。

INSERT INTO system_config (config_key, config_value, description)
VALUES
  ('edge.driver.type', 'chzh8',
   '柜机驱动型号（chzh8/jinyu2/yichu2）。默认 chzh8 = 与原写死行为一致；未知型号必须显式拒绝，不许静默回落'),
  ('consumer.order_search.enabled', 'false', '消费端订单列表搜索框（默认关）'),
  ('consumer.coupon_entry.enabled', 'false', '消费端首页券包入口（默认关；关闭时仅「我的」页有）'),
  ('consumer.product_detail.enabled', 'false', '消费端商品详情弹层（默认关）'),
  ('merchant.charts.enabled', 'false', '商户端经营分析图表（默认关）'),
  ('consumer.pay_channel_select.enabled', 'false', '消费端结算页主动选支付方式（默认关；关闭时服务端自动决策渠道）'),
  ('consumer.ad_banner.enabled', 'false', '消费端首页推广位总闸（默认关；开则渲染自有活动/腾讯广告/占位图，按优先级）'),
  ('consumer.wx_ad.enabled', 'false', '消费端腾讯流量主广告（默认关；还需 unit_id 非空才渲染）')
ON CONFLICT (config_key) DO NOTHING;
