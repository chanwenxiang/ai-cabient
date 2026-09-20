# F4 广告变现闭环 · 设计稿

> 状态：**切片 1 的「站位」部分已落地**（2026-09-20）：S1 已纳入开关门控 + 占位图；
> 广告主 / 投放订单 / 计费 / 报表 / 入账仍按本稿口径待推进。关联路线图条目 `F4`（`COMPETITOR_BENCHMARK_AND_ROADMAP_2026-09-17.md:207`）。
> 本稿先把「库存到底是什么」这条前提锁死，再谈计费。
> 落地记录见 **§10**。

---

## 0. 前提更正：本项目**没有柜机屏**，库存也不在柜屏上

**核实结论（2026-09-20，逐条取证）：**

| 事实 | 证据 |
|------|------|
| 边缘端**无屏幕/播放能力**（无解码、无渲染、无 HDMI/kiosk） | `edge/android-app` 全仓 `ExoPlayer\|VideoView\|MediaPlayer\|SurfaceView\|kiosk\|hdmi` **0 命中** |
| 只有**模拟器在假装**柜机屏播放器 | `edge/device-simulator/src/main/java/.../DeviceSimulator.java:169` `startAdScreenLoop()`「柜机屏播放器模拟」 |
| **真实广告位早已落在消费者小程序首页** | `clients/consumer-mp/src/pages/index/index.vue:200` `<DeviceAdBanner v-if="deviceId" :device-id="deviceId" />` |
| 该组件**已实现且已计量** | `clients/consumer-mp/src/components/device-ad-banner.vue`：拉 `screen-content` → `swiper` 轮播 IMAGE/VIDEO → 回写 `IMPRESSION` / `COMPLETE`（按 `durationSeconds` 计时）/ `CLICK` |

⇒ **库存 = 消费者微信小程序首页的横幅轮播位（用户自己的手机屏）**，不是柜体屏。
⇒ 路线图里「屏幕广告变现」的「屏幕」应读作**用户端屏幕**；柜机没有屏幕**不阻断**本项。

### 命名债（必须校正，否则后续会持续误判）

现有代码/注释仍带「柜屏」心智模型，与真实消费端不符：

- `DeviceInternalController.java:54` —— `/** 柜屏曝光/完播回写（ROI 留痕）。 */`
- `DeviceController.java:77` —— `/** 开门页/柜机屏：拉取当前生效的投放轮播（需登录）。 */`（这句是**对的**，点出了「开门页」）
- `DeviceSimulator.java:169` —— 「柜机屏播放器模拟」

**建议**：后续统一改为「**小程序展示位**」；`screen-content` 这一 API 名属历史包袱，**保留不改**（改名会牵动三端契约与 OpenAPI 生成物），但在文档与注释里标注真实语义。

---

## 1. 现状盘点

| 环节 | 状态 | 落点 |
|------|------|------|
| 素材库 | ✅ 已实现 | `media_asset`、`MediaAssetService`、`MediaController`、运营台 `AdAssetsView.vue` |
| 投放计划（增删改查/启停/投放范围） | ✅ 已实现 | `ad_campaign` + `ad_campaign_item` + `ad_campaign_device`、`AdCampaignService`、`OpsAdController`、`AdCampaignsView.vue` |
| 投放下发（按设备取当前轮播） | ✅ 已实现 | `GET /{deviceId}/screen-content` → `ScreenContentDto` |
| 曝光/完播/点击上报 | ✅ 已实现 | `ad_play_event`、`POST /{deviceId}/ad-play`、`AdCampaignService.recordPlayEvent:214` |
| 曝光数统计（粗） | ⚠️ 仅计数 | `AdCampaignService` `toDto` 内 `countByCampaignAndType(...IMPRESSION)` |
| **广告主 / 投放订单** | ❌ 缺失 | 全仓 `advertiser\|广告主` 实现侧 0 命中 |
| **CPM/CPC 计费** | ❌ 缺失 | 全仓 `\bcpm\b\|\bcpc\b` 0 命中 |
| **投放报表（广告主视图）** | ❌ 缺失 | `OpsAdController` 无任何统计/报表端点 |
| **广告收入入账** | ❌ 缺失 | 无表、无账本、无对账 |

### 历史注记（为什么这次不能只建表）

`V81__ad_management.sql` **曾建过完整版**：`ad_slot`(含 `default_price`)、`ad_campaign`(含 `advertiser_id`/`budget`/`spent`/`slot_id`)、`ad_impression`(含 `cost`)。
**`V155__drop_orphan_legacy_tables.sql:4-6` 把这三张表当孤儿 DROP 了** —— 迁移头理由：「Java 功能栈 never wired to services/APIs」（**设计了但从未接线**）。
随后 `V174` 重建了简化版 `ad_campaign`（去掉了广告主与预算字段），即当前形态。

⇒ **F4 本质＝把当年设计过的那套重新接起来**；区别在于这次**每一张新表必须有真实写入方与读取方**，不得再留孤儿表（参见 `docs/ORPHAN_TABLE_DISPOSITION.md` 的教训）。

---

## 2. 可售库存清单

| 位号 | 位置 | 状态 | 说明 |
|------|------|------|------|
| **S1** | 消费者小程序首页横幅轮播 | ✅ 已实现（**已纳入门控**） | 首发库存；`device-ad-banner.vue`，220rpx 高，多素材自动轮播 |
| S2 | 结算页 / 支付结果页 | ⬜ 待建 | 下单后等待期，注意力集中，转化价值高 |
| S3 | 商户端小程序位 | ⬜ 待建 | 面向供应商/服务商（B 端广告主天然匹配） |
| — | 柜身贴纸 / 灯箱 | ➖ 不属本项 | 线下物料，需人工，非软件库存 |

### S1 的门控与站位（已落地）

原状态：`index.vue:200` 只有 `v-if="deviceId"`，**没有任何开关**，与其他消费端扩展功能（`consumer.product_detail.enabled` 等）的约定不一致 ⇒ **关不掉**。现已修正：

- 开关键 **`consumer.ad_banner.enabled`**（默认 `false`，fail-closed）。
  ⚠️ 本文早期草拟的键名是 `ad.banner.enabled`；**最终采用 `consumer.*` 命名空间**，理由：本开关控的是**消费端界面可见性**，与 `consumer.order_search.enabled` / `consumer.product_detail.enabled` 同族，应同组同前缀（运营台「扩展功能」组），不要为广告单独开一个顶层域。
- **三态语义**（开关开启后）：

  | 条件 | 渲染 |
  |------|------|
  | 该柜有生效中的投放计划 | 真实素材轮播（曝光/完播/点击照常上报） |
  | 没有投放内容 | **占位图**（`static/ad/slot-placeholder.png`，由 `scripts/render-mp-assets.mjs` 生成） |
  | 开关关闭（默认） | **整块不渲染** —— 与接入前逐字节一致 |

- 🔴 **占位图不上报任何事件**（不绑定点击、不发 `ad-play`）：占位不是广告，不该产生计量数据。
  计量即计费依据 —— 一旦占位也上报，上线计费后就会凭空多出「收入」。

---

## 3. ⚠️ 前置确认项（工程不能替产品/法务决定）

1. **合规**：在小程序内向**第三方**广告主售卖展示位，是否落入微信小程序运营规范的受限范围？自有/关联方推广（如平台自己的活动、合作品牌）与第三方付费广告，风险等级不同。
2. **资质/财税**：向广告主收费涉及广告服务收入，是否需要对应经营资质、发票与税务口径。
3. **数据合规**：曝光上报目前携带 `device_id`；若后续要按「人」定向或归因，涉及个人信息，需另评估。
4. 🔴 **广告可识别性（《广告法》要求）**：投放素材必须带**可识别的广告标识**（本项目截图中的 `流量主` 广告即带「广告」角标）。
   现状：`device-ad-banner.vue` 渲染真实素材时**没有加标识**。占位图自带「广告」角标，但**真实投放素材的标识尚未实装** ⇒
   **切片 2 之前必须补**（否则一旦对外收费即为违规投放）。这是**工程可做**的一项，不需要等外部结论。

⇒ 在上述未定之前，**先落地「只统计不结算」的第一版是安全的**（不触碰资金，不构成收费行为）。见 §8 D4。

---

## 4. 数据模型（建议）

```
advertiser                     广告主
  advertiser_id   BIGSERIAL PK
  name            VARCHAR(128) 广告主名称
  contact_name    VARCHAR(64)  联系人
  contact_phone   VARCHAR(32)  联系电话
  status          VARCHAR(16)  ACTIVE | DISABLED
  created_at / updated_at

ad_order                       投放订单（把「投放计划」商品化）
  order_id        BIGSERIAL PK
  advertiser_id   BIGINT  → advertiser（FK）
  campaign_id     BIGINT  → ad_campaign（FK，复用既有投放计划）
  billing_mode    VARCHAR(8)   CPM | CPC
  unit_price_cents BIGINT      每千次曝光 / 每次点击 的单价（分）
  budget_cents    BIGINT       预算上限（分）
  spent_cents     BIGINT NOT NULL DEFAULT 0   已消耗（分）
  slot_code       VARCHAR(32)  库存位号（S1/S2/S3）
  start_at / end_at
  status          VARCHAR(16)  DRAFT | RUNNING | EXHAUSTED | STOPPED
  created_at / updated_at

ad_billing_entry               计费流水（幂等落在这一层）
  entry_id        BIGSERIAL PK
  order_id        BIGINT  → ad_order
  event_id        BIGINT  → ad_play_event（唯一，幂等键）
  event_type      VARCHAR(16)  IMPRESSION | CLICK
  qty             INT          本次计量数量（曝光=1；CPM 汇总时=1000）
  amount_cents    BIGINT       本次计费金额（分）
  occurred_at     TIMESTAMPTZ
  UNIQUE (event_id)            ← 🔴 同一播放事件只能计费一次

ad_revenue_ledger              广告收入账本（独立，不挂 RevenueSplit）
  ledger_id       BIGSERIAL PK
  order_id        BIGINT
  entry_id        BIGINT       来源计费流水（可空，汇总记账时为空）
  kind            VARCHAR(16)  BILLING | ADJUSTMENT | REFUND
  amount_cents    BIGINT
  occurred_at     TIMESTAMPTZ
```

### 为什么广告收入**不挂** `RevenueSplit`

`RevenueSplitService.recordSplit(CabinetOrder)` 的签名与实体 `OrderRevenueSplit` **以订单为锚**（按 `order_id` 分账、随退款 `voidSplitOnFullRefund` / `adjustSplitAfterPartialRefund` 联动）。
广告收入**没有订单**，硬挂只能造「假订单」，会污染订单表与资金对账口径。

⇒ **独立 `ad_revenue_ledger`**；若未来需要并入平台总账，在**报表层**做汇总，而不是在**存储层**伪造关联。

---

## 5. 计费口径（需拍板，附推荐）

| 议题 | 推荐 | 理由 |
|------|------|------|
| CPM 计量粒度 | **按曝光逐次累加，跨满 1000 次才产生一条 `qty=1000` 的计费流水**；未满 1000 的部分**保留在余额**，不预收 | 避免「999 次也按 1 千次收费」的争议；订单结束时可选择**按实际不足千次比例结算或抹零**（→ D3） |
| CPC 计量 | 按 `CLICK` 事件逐次计费，单价 = `unit_price_cents` | 直接 |
| 预算控制 | 每次计费后累加 `spent_cents`；`spent_cents >= budget_cents` ⇒ 订单转 `EXHAUSTED` 并**从 `screen-content` 下发中剔除**（fail-closed，停投） | 防止超投产生无法收费的曝光 |
| 幂等 | `ad_billing_entry.event_id` **唯一键** | 上报接口可重试；重复事件不得重复计费 |
| 🔴 服务端防刷 | `recordPlayEvent` **必须补**服务端去重 + 频次上限 | 现状：`device-ad-banner.vue` 只用**客户端内存 Set**（`impressed`）防重复，**可被绕过**；服务端当前**无任何限制** ⇒ 曝光可刷，直接等于可刷钱 |

⚠️ **防刷是本项的生死线**：计费一旦上线，「曝光的可信度」就等于「收入的真实性」。建议切片 2 必须与服务端限流同批上线，不得后补。

---

## 6. 对账

- `Σ ad_billing_entry.amount_cents (BY order)` **应恒等于** `σ ad_revenue_ledger.amount_cents (BY order, kind=BILLING)`。
- 建议加**静态门禁**（沿用本项目 `check-*` 惯例）：校验两张表的写入点成对出现，避免「记了流水没入账」或反之。

---

## 7. 分片计划

| 切片 | 内容 | 是否触碰资金 | 状态 |
|------|------|------------|------|
| **1** | 开关 `consumer.ad_banner.enabled`（含把既有 S1 纳入门控）+ 占位图 | 否 | 🟡 **站位部分已落地**（本文 §10）；广告主 + 投放订单 CRUD + 运营台页面待做 |
| **2** | 计费引擎：CPM/CPC + 幂等 + 预算停投 + **服务端防刷** + 素材「广告」标识 | 否（只记账） | ⬜ 待做 |
| **3** | 投放报表：运营台（按订单/广告主/设备/时段）+ 广告主视图 | 否 | ⬜ 待做 |
| **4** | 收入入账 `ad_revenue_ledger` + 对账门禁 | ✅ 是 | ⬜ 待做 |

切片 1–3 不构成对外收费，可在合规确认前推进；**切片 4 必须在 §3 前置项落定后再动**。

---

## 8. 待确认决策（带推荐）

| # | 议题 | 我的推荐 |
|---|------|---------|
| **D1** | 首发库存范围 | **只做 S1（小程序首页横幅）**，验证计费链路后再扩 S2/S3 |
| **D2** | 计费模式 | **先做 CPM**（曝光量已在采，口径最稳）；CPC 留接口占位，切片 3 再开 |
| **D3** | 不足千次曝光 | **按实际次数按比例结算**（`amount = ceil(qty * unit_price / 1000)`），订单终态时结清 |
| **D4** | 是否先做「只统计不结算」版 | **是** —— 切片 1+2 只记账不出账，合规未定前不收费 |
| **D5** | S1 开关默认值 | `false`（fail-closed；与其他消费端扩展功能一致） |

---

## 9. 本项必须一并处理的既有问题

1. ~~**S1 无开关门控** —— `index.vue:200` 裸挂，需接入开关。~~ ✅ **已修**（`consumer.ad_banner.enabled`，见 §2）。
2. **上报无服务端防刷** —— `AdCampaignService.recordPlayEvent:214` 无去重/限流。
3. **命名债** —— 「柜屏」相关注释与真实消费端不符，需校正（§0）。
4. **`GET /{deviceId}/screen-content` 不下发计费信息** —— 计费上线后，前端**不得**收到单价/预算（避免客户端推断与作弊），计价**必须**全在服务端。
5. **素材无「广告」标识** —— 真实投放渲染时需补（§3 第 4 条，切片 2 前置）。

---

## 10. 落地记录

### 切片 1 · 站位（2026-09-20）

**改了什么**

| 层 | 文件 | 内容 |
|----|------|------|
| 后端 | `SystemConfigService.java` | 常量 `CONSUMER_AD_BANNER_ENABLED` + `ensureDefaults()` seed（`"false"`）+ `consumerPublicConfig()` 下发 `adBannerEnabled` |
| 后端 | `resources/ops/feature-flags.json` | 注册表登记（组「扩展功能」，`BOOLEAN`，默认 `false`） |
| 前端 | `utils/feature-flags.ts` | `adBannerEnabled()` 读取器（fail-closed） |
| 前端 | `pages/index/index.vue` | 广告位改为 `v-if="deviceId && adBannerVisible"`；`adBannerVisible` 由公开配置喂入 |
| 前端 | `components/device-ad-banner.vue` | 新增占位分支（`loaded && !items.length`）+ 占位**不上报任何事件** |
| 资产 | `scripts/render-mp-assets.mjs` | 新增占位图渲染（750×220，与 `220rpx` 等比）→ `consumer-mp/src/static/ad/slot-placeholder.png` |
| 测试 | `SystemConfigExtensionFlagsTest` / `feature-flags.test.ts` | 下发默认关 / 跟随存储值 / blank 仍关；前端 fail-closed、取值边界、**开关互相独立**（防读错键） |

**不变量（本切片刻意守住的）**

- 默认关时，首页**不渲染**广告位 —— 与接入前逐字节一致。
- 前端拿不到配置（网络失败/字段缺失）一律按「关」（`loadConsumerFlags` 失败 ⇒ 缓存空表）。
- 占位图**零计量**：无点击、无 `ad-play` 上报。
- 页面门控用**页面级 ref**（`adBannerVisible`）而非模板内直接调函数 —— 与 `couponEntryVisible` / `detailVisible` 的既有姿势一致，避免模板里出现不可测的副作用。

**验证证据**

- 前端：`node node_modules/vitest/vitest.mjs run` ⇒ **43 passed**（基线 42 + 新增 1）。
- 门禁：`node scripts/check-feature-flags.mjs` ⇒ **OK：注册表 72 个开关 / 16 个分组，下发键 21 个前端全部消费**。
- **A/B 注入漂移（证明新判据真的会红，且精确命中）**：
  - A「`adBannerEnabled()` 恒返回 `true`（丢掉 fail-closed）」⇒ 精确红 3 例（缓存未热 / 取值边界 / 请求失败）。
  - B「`adBannerEnabled()` 误读 `orderSearchEnabled`（复制粘贴改错键）」⇒ 红 2 例，其中**「开关互相独立」正是为抓这类漂移而新增**。
  - 两次注入后均按 md5 逐字节还原（`bcb26ceec1a359b51b69824fd024e096`）。
- 样式/静态检查：`prettier --check` 全过；`eslint .` **0 error**（31 条 warning 均在既有 `merchant-mp` 文件）。

**运维怎么用**

1. 运营台 → 系统配置 → 「扩展功能」组 → `consumer.ad_banner.enabled` 打开 ⇒ 首页立刻出现**占位图**（验证位置与样式）。
2. 在投放计划里给某柜配置生效中的素材 ⇒ 该柜首页自动换成**真实素材**；其他没配的柜仍是占位图。
3. 关闭开关 ⇒ 恢复「不渲染」。

> ⚠️ 小程序的**广告账户 / 商家号**尚未就绪，本切片刻意**不需要**它们：占位图是纯静态资源，
> 开关是纯配置。等账户与素材就绪，只需**配置投放 + 打开开关**，无需再改代码。
