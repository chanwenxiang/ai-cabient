# AI Cabinet 代码清单（Pass 2 · 精确测试用）

> **用途**：在 [CODEBASE_FOUNDATION.md](CODEBASE_FOUNDATION.md) 之上，把「有哪些代码」落到**文件 / 端点 / 类**级，供精确测试与优化点名。  
> **生成**：2026-09-07 Pass 2  
> **统计口径**：排除 `node_modules` / `target` / `dist` / `.venv` / `static/admin/assets` 后，约 **2206** 个源文件（`.java` 1246、`.sql` 267、`.vue` 137、`.ts` 103 等）。

**使用约定**：改某一域时，先在本清单定位类/端点/页面，再打开源码细读并补测；不要假设「架构通读 = 已掌握每一行」。

---

## 1. 规模总表

| 区域 | 规模 |
|------|------|
| trade-service Controllers | **69**（另有 2 个 SPA UI Controller） |
| trade 对外/内部端点 | ~**594** |
| trade `*Service.java` | **137**（其中 **13** 个 >800 LOC） |
| trade domain 实体 | **147**（约 139 有 Mapper；76 有 XML） |
| trade `*Test.java` | **201** |
| device-service 主代码 | **15** 类 |
| vision-service 路由 | **9** HTTP |
| common-core DTO | **365**；枚举 **4** |
| admin-vue 视图 | **72** views + layout |
| consumer-mp / merchant-mp | **23** / **22** pages |
| shared packages | 5 |

---

## 2. trade-service · God 类（优化第一刀）

| Service | LOC（约） | 域 | 状态 |
|---------|----:|----|------|
| AdminDashboardService | 门面偏瘦 | 运营工作台 | Pass 3F：Analytics/Audit/Device/Catalog/SessionOrder/Workbench/MemberFinance **已抽** |
| MerchantPortalService | 门面偏瘦 | 商户门户 | Pass 3F：Workbench/Device/Inventory/Team/Finance **已抽** |
| ReplenishmentService | ~1655 | 补货调度 | 仍大；Ops 侧经 `OpsReplenishmentAdminService` |
| SessionService | ~1453 | 购物会话 | 3A/3C 约束 |
| DisputeService | ~1359 | 争议 | 3B |
| WarehouseService | ~1328 | 仓储 | Ops 侧经 `OpsWarehouseAdminService` |
| SettlementService | ~1297 | 结算 | 3A `SettlementDecision` |
| DeviceSlotService | ~1231 | 货道/排面 | 3D |
| DataConsistencyService | ~954 | 一致性巡检 | — |
| PaymentService | ~955 | 充值/回调 | 3A |
| InventoryLotService | ~938 | 批次 FEFO | 3D |
| OpsRbacService | ~872 | RBAC | Ops 经 `OpsRbacController` 直连 |
| CompetitiveGapService | ~867 | 缺口能力集 | — |

**运营 API 面（Pass 3F 已拆）**：`OpsOrg` / `OpsRbac` / `OpsWarehouse` / `OpsReplenishment` / `OpsAd` / `OpsProcurement` / `OpsRisk` / `OpsOta` / `OpsFinance` / `OpsDeviceEnv` / `OpsAnalytics`；`OpsCommercialController` **仅** `POST …/commercial-flow/run`。旧 `OpsCommercialFacade` / `OpsCommercial*Support` **已删**。

**仍大的 Controller**：`MerchantPortalController`(~78)、`AdminDashboardController`(~43)。

---

## 3. trade-service · API 域索引

### 3.1 鉴权 `/api/v2/auth` — `AuthController`
captcha、sms-code、login / admin-login / password-* / merchant-password-login、wx-login / wx-h5-login / alipay/login、2FA verify/recovery、refresh、logout、server-boot。

### 3.2 消费者核心
| 前缀 | Controller | 要点 |
|------|------------|------|
| `/api/v2/sessions` | SessionController | 开门、取消、cart、demo-close、live-cart、order |
| `/api/v2/orders` | OrderController | 列表/详情/视频/pay/refund/invoice |
| `/api/v2/devices` | DeviceController | nearby、status、products、screen、fault |
| `/api/v2/account` | AccountController | 余额、实名、支付分/支付宝协议 |
| `/api/v2/payment` | PaymentController + WeChat/Alipay Notify + DevMock* | 充值与回调（mock 仅 dev） |
| `/api/v2/disputes` | ConsumerDisputeController | 发起/证据 |
| `/api/v2/member` `/coupons` `/marketing` | Member/Coupon/Marketing | 积分券活动 |
| `/api/v2/public` `/dicts/runtime` `/media` | Public/Dict/Media | 公开配置与媒体 |
| `/o/{deviceId}` | CabinetOpenLandingController | 扫码落地 |

### 3.3 内部 `/internal/v1`
| 前缀 | 用途 |
|------|------|
| `/sessions/door-event|video|video-upload-url|gravity-deltas|{id}/live-cart` | 门事件与视频/重力 |
| `/devices/{id}/exists|heartbeat|screen-content|ota/check|inventory-snapshot` | 设备存在性与运维 |
| `/vision/anomaly-events` + `/vision/mappings|…` | 异常与映射 |
| `/sms/latest-code` `/demo/*` | E2E/演示 |

### 3.4 运营 `/api/v2/ops…`
- `AdminDashboardController`：统计/设备/会话/订单/用户/SKU/报表/审计（~43）
- **按域 Ops Controllers**（同前缀 `/api/v2/ops/admin`）：Org、Rbac、Warehouse、Replenishment、Ad、Procurement、Risk、Ota、Finance、DeviceEnv、Analytics；配套 `Ops*AdminService`（权限校验）
- `OpsCommercialController`：仅演示 `commercial-flow/run`
- `OpsGapFeaturesController` / `AdminGrowthController` / `OpsExceptionController`
- 财务：余额退款、开票、商户分账/进件/钱包/提现、线长、场地租金、流量费
- 争议 `DisputeController`、维修 `RepairTicketController`、审批/字典/配置/定时任务/一致性/仓调拨等

### 3.5 商户 `/api/v2/merchant`
`MerchantPortalController`(~78)：工作台、柜机、订单视频、争议、补货、定价、结算导出、钱包/线长钱包、团队；另 notifications / announcements / exceptions。

---

## 4. trade-service · 定时任务

| 类型 | 代表 |
|------|------|
| 独立 Scheduler | UnpaidOrder、RechargeOrder、Reconciliation、ProfitSharingRetry、LineCommission、FinanceMarginLock、OpsFeeBill、DeviceAvailability、Compensation、DisputeSla、ExpiryAlert、ReplenishmentTimeout、Points/Coupon 到期、SkuReview、GrowthLogArchive、MerchantWorkbenchNotify |
| XXL | `ScheduledTaskXxlJobHandler`（启用时接管部分任务） |
| Service 内嵌 @Scheduled | SessionService 超时扫、DevicePresence 离线、DeviceTempPlan、Coupon 过期、DataConsistency、OpsExceptionScanner、RiskAutoDisposition、SlaMetrics |

---

## 5. trade-service · 测试资产

| 包 | 约数 | 侧重 |
|----|-----:|------|
| `service/` | ~178 | 大量 `*ConcurrencyTest` + 结算/退款/补货行为 |
| `e2e/` | 3 | Admin/Consumer/Merchant MockMvc |
| `integration/` | 2 | 对账、微信回调 |
| `auth`/`payment`/`reconciliation`/`security` | 若干 | 鉴权、签名、CIDR |

**孤儿服务**：`IdempotencyService`（有测、无业务注入）→ 接线或删除。

---

## 6. device-service（完整）

| 类 | 职责 |
|----|------|
| DeviceInternalController | open-door / commandStatus / set-target-temp / ops-command |
| DeviceCommandService + DeviceCommandTracker | 指令与 ACK（15s） |
| MqttCommandPublisher / MqttEventListener | MQTT 上下行 |
| DoorEventDeduplicator / MqttConnectionRegistry | 去重与连接健康 |
| TradeServiceClient | 转发 door/heartbeat/video/exists |
| DeviceMqttMetrics / MqttHealthIndicator | 指标与健康 |
| ProductionStartupValidator | 生产密钥/MQTT SSL 门禁 |

**测试**：`DeviceCommandTrackerTest`、`DoorEventDeduplicatorTest`、`MqttEventListenerDoorTest`、`EmqxSharedSubscriptionIT`（Q7 broker，`disabledWithoutDocker`）等；见 Pass 3C。

---

## 7. vision-service（完整路由）

| 路由 | 说明 |
|------|------|
| `GET /health` `/health/detail` | 探活 |
| `POST …/recognize` `/recognize/upload` | 识别（默认 mock） |
| `POST …/suggest-class` `/dispute-suggest` | DeepSeek 辅助 |
| `POST …/debug/force-need-review` | E2E 强制争议 |
| `POST …/recognize/async` + `GET …/tasks/{id}` | 异步桩 |

Backend：`mock`（默认）| `quectel`（占位）| DeepSeek（建议路由）；`yolo*` 已弃用回落 mock。

---

## 8. common-core

- `dto/` **365**；`enums/` SessionState / DoorState / UploadStatus / RefundPolicy
- `constants/` CabinetConstants、KafkaTopics、PayChannels、InternalApiConstants
- `security/` InternalApiAuthInterceptor、CidrAllowlist
- `mqtt/MqttTopics`、`storage/ObjectStorageKeys`

---

## 9. 前端完整面

### 9.1 admin-vue
- **路由权限不在 router meta**，而在 `config/menu.ts` + `findNavByPath` 守卫
- 约 **60+** 业务路由；见菜单组：概览 / 交易履约 / 设备商品 / 履约仓储 / 财务商户 / 增长风控 / 系统
- API 仅 `src/api/client.ts`（封装 shared-api）
- Vitest：**3** 个 utils 测；无页面级单测

### 9.2 consumer-mp（23）
首页扫码、订单、我的 + login/verify/recharge/result/video/dispute/coupons/member/points/messages/marketing/nearby/help/policy/announcements/feedback/report…

### 9.3 merchant-mp（22）
工作台/柜机/待办/我的 + replenishment/request/pricing/orders/disputes/settlements/wallet/line-wallet/splits/team/business…  
导航权限：`config/merchant-nav.ts`（field/biz/team packs）

### 9.4 shared-*
types(~81 导出) / api(ApiClient) / dict(DICT+runtime) / rbac(matchPermission) / uni(request,qrcode,format,status-bar…)

---

## 10. edge

| 组件 | 要点 |
|------|------|
| android-app | OPEN_DOOR → 锁 + CameraX → MinIO → DOOR 事件；mockDebug/deviceDebug |
| device-simulator | 全指令集 + 视频/重力/心跳；联调主替身 |

---

## 11. 精确测试怎么用本清单

1. **点名改动** → 在本文件找到 Controller/Service/页面  
2. **打开源码** → 读完目标类与直接依赖（Mapper/Client）  
3. **选测** → 已有 `*Test` / `e2e-*.ps1` / Playwright；缺口按 foundation §10 补  
4. **优化** → 优先 §2 仍大的 Service；OpsCommercial Controller/Facade 拆分已收官（见 Pass 3F）  

### 建议的后续 Pass（按域深读源码正文）

| Pass | 域 | 必读文件 | 笔记 |
|------|-----|----------|------|
| **3A** | 金钱正确性 | SessionService, SettlementService, OrderPaymentService, ConsumerPreauthService, PaymentService, WeChat/Alipay Notify | **[PASS_3A_MONEY.md](pass-notes/PASS_3A_MONEY.md)** ✅ |
| **3B** | 争议退款 | DisputeService, Settlement confirm/waive/partial, 三端 dispute | **[PASS_3B_DISPUTE.md](pass-notes/PASS_3B_DISPUTE.md)** ✅ |
| **3C** | MQTT | device MqttEventListener, TradeServiceClient, SessionInternalController, simulator | **[PASS_3C_MQTT.md](pass-notes/PASS_3C_MQTT.md)** ✅ |
| **3D** | 库存货道 | InventoryService, DeviceSlotService, InventoryLotService, RestockSnapshot | **[PASS_3D_INVENTORY.md](pass-notes/PASS_3D_INVENTORY.md)** ✅ |
| **3E** | 商户资金 | RevenueSplit, MerchantWallet, MerchantWithdraw, ProfitSharing | **[PASS_3E_MERCHANT_WALLET.md](pass-notes/PASS_3E_MERCHANT_WALLET.md)** ✅ |
| **3F** | 运营神类 | AdminDashboard / MerchantPortal 切片 + Ops Controller/AdminService 拆分（Facade 已删） | **[PASS_3F_OPS_GOD_CLASSES.md](pass-notes/PASS_3F_OPS_GOD_CLASSES.md)** ✅ 落地 |

每完成一个 Pass，把「已读类 + 发现的分支表 + 补测清单」写入 `docs/pass-notes/`。

---

## 12. 与 foundation 的关系

| 文档 | 回答什么 |
|------|----------|
| [CODEBASE_FOUNDATION.md](CODEBASE_FOUNDATION.md) | 为什么这样架构、测什么优先级、怎么起环境 |
| **本文** | 仓库里**具体有哪些**类/端点/页面 |
| 源码本身 | 每一行逻辑（点名后必读） |

---

*Pass 2 清单随代码变更维护：新增 Controller/Service/页面时同步登记。*
