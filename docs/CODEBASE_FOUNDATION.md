# AI Cabinet 代码库基础手册

> **用途**：通读全仓后的结构化底稿，供后续**测试设计、回归、性能优化、重构优先级**直接引用。  
> **生成日期**：2026-09-07  
> **范围**：后端 / 前端 / 视觉 / 边缘 / 基础设施 / 脚本 / 现有测试资产  
> **不替代**：日常启动请看 [STARTUP_REFERENCE.md](STARTUP_REFERENCE.md)；模块路径请看 [MODULES.md](MODULES.md)；上线执行请看 [GO_LIVE_EXECUTION_PLAN.md](GO_LIVE_EXECUTION_PLAN.md)。  
> **精确测试用文件级清单**：[CODEBASE_INVENTORY.md](CODEBASE_INVENTORY.md)（Pass 2：端点/Service/页面全表）。

---

## 0. 一句话定位

**AI 开门柜**：消费者扫码开门取货 → 视觉/端侧识别 → 自动结算扣款；运营后台管设备/SKU/争议/财务；商户端管补货/定价/钱包。本仓是独立于旧系统 `ego-automat` 的新实现（业务与硬件协议可参考旧仓，不依赖其代码）。

---

## 1. 仓库规模速览（2026-09 盘点）

| 维度 | 规模 |
|------|------|
| trade-service Controllers | ~69 |
| trade-service `*Service` | ~137 |
| Flyway 迁移 | **264** 个脚本，最新 **V265** |
| trade-service 单测/并发测 | ~**202** 个 `*Test.java`（含大量 `*ConcurrencyTest`） |
| device-service Java 源文件 | ~15（薄 MQTT 桥） |
| admin-vue 业务视图 | ~70 个 `.vue` |
| consumer-mp pages | 23 |
| merchant-mp pages | 22（含 4 Tab） |
| shared packages | 5（types / api / dict / rbac / uni） |
| E2E PowerShell | 16+（`scripts/e2e-*.ps1`） |
| Playwright UAT | admin / consumer H5 / merchant H5 / 三端脚本 |

---

## 2. 逻辑架构

```
消费者小程序 / 商户小程序 / 运营控制台(admin-vue)
                    │  HTTP /api/v2  (+ JWT)
                    ▼
            ┌───────────────┐
            │ trade-service │  :8080（全栈 Docker 常为 :18080）
            │ 会话·订单·结算 │  Postgres Flyway · Redis/Redisson · Kafka(可选)
            │ 运营·商户门户 │
            └───────┬───────┘
     /internal/v1   │ RestClient + X-Internal-Api-Key
   ┌────────────────┼────────────────┐
   ▼                ▼                ▼
device-service   vision-service    MinIO / 微信·支付宝
:8081 MQTT桥     :8082 FastAPI     视频 / 支付回调
   │                │
   ▼                ▼
 EMQX :11883    mock / DeepSeek争议辅助
   │                （生产意图：端侧 Quectel 识别上报）
   ▼
edge: device-simulator | android-app
```

### 2.1 模块职责边界

| 路径 | 职责 | 不该做的事 |
|------|------|------------|
| `services/trade-service` | 领域大脑：会话状态机、结算、支付、RBAC、仓储、钱包、争议 | 直接连 MQTT |
| `services/device-service` | MQTT 指令下发 / 事件回传；命令跟踪与去重 | 持有业务库、扣款 |
| `services/common/common-core` | 共享 DTO / 枚举 / 内部鉴权 / MQTT topic 常量 | 业务逻辑 |
| `vision-service` | 开发 mock 识别 + 争议辅助；可选 Kafka worker | 生产端侧 YOLO（已废弃云端模型路径） |
| `clients/admin-vue` | 运营控制台；构建产物进 trade `static/admin` | — |
| `clients/consumer-mp` | 扫码开门、订单、充值、争议 | — |
| `clients/merchant-mp` | 补货、柜机、钱包、分账、团队 | — |
| `packages/shared-*` | 类型 / API 客户端 / 字典 / RBAC / uni 工具 | 业务页面 |
| `edge/*` | 柜机端真实/模拟硬件 | 业务结算 |
| `infra/` | Compose、网关、监控、镜像 | — |

### 2.2 两种本地模式（测试前必须先选）

| 模式 | 启动 | trade 端口 | 注意 |
|------|------|------------|------|
| **A. IDEA + 仅 infra**（日常开发推荐） | `docker compose -p ai-cabinet -f infra/docker-compose.yml up -d` + IDEA trade/device + uvicorn vision | **:8080** | 不要同时 `docker-up.ps1` |
| **B. 全栈 Docker** | `.\docker-up.ps1`（`docker-compose.full.yml` + Win 端口 overlay） | **:18080** | 含 simulator / XXL-JOB；勿再起 IDEA trade |

`scripts/e2e-lib.ps1` 会优先探测存活的 `:8080`，否则用 `:18080`。**所有测试计划必须写明模式与 BaseUrl。**

---

## 3. 核心业务链路（测试主路径）

### 3.1 购物闭环（金钱正确性最高优先级）

```
扫码/登录 → POST /api/v2/sessions
  → 预授权冻结 (ConsumerPreauthService)
  → DeviceServiceClient.openDoor → MQTT OPEN_DOOR
  → 门开事件 → Session SHOPPING
  → 门关 + 视频上传 MinIO
  → SettlementService → VisionServiceClient.recognize
  → 高置信：出单 + 扣库存 + OrderPaymentService.charge
  → 低置信/映射失败/视觉不可用：DISPUTED + DisputeTicket（通常先不扣款）
```

**会话状态机**（`SessionState.canTransitionTo`）：

```
CREATED → OPENING → SHOPPING → WAITING_UPLOAD → RECOGNIZING → SETTLING → COMPLETED
                              ↘ DISPUTED / FAILED / CANCELLED（受守卫约束）
```

**关键锁键（Redisson，前缀 `aicabinet:lock:`）**：

| 场景 | 典型 key 形态 |
|------|----------------|
| 同柜并发开门 | `session:open:{deviceId}` |
| 会话生命周期 | `session:life:{sessionId}` |
| 结算 | `session:settle:{sessionId}` |
| 订单支付 | `order:payment:{orderId}` |
| 库存 | 设备维度 `device:...`（见 `InventoryService`） |
| 分账 | `order:split:{orderId}` |

### 3.2 争议

- 自动：结算 `need_review` / 重力不一致 / 视觉熔断
- 消费者：`POST /api/v2/disputes` + 证据上传
- 运营：`/api/v2/ops/disputes/{id}/claim|resolve|close|reopen`
- 商户：`/api/v2/merchant/disputes/*`
- 结案可确认商品 / 免单退款 / 部分退款（`SettlementService` / `DisputeService`）

### 3.3 补货

商户申请 → 运营受理成任务 → 签到 → 开门（操作员会话，**不结算购物**）→ 确认行 → 完成 + 证据。  
运营捷径：`POST /api/v2/ops/restock/open-door`。可选库存快照识别模式。

### 3.4 商户钱包 / 提现

订单分账入账（`RevenueSplitService`）→ 钱包余额/冻结（`MerchantWalletService`，流水幂等）→ 提现审核打款（`MerchantWithdrawService`，dev 可 mock）。

### 3.5 支付通道

| 场景 | 通道 | 备注 |
|------|------|------|
| 开门预授权 / 余额扣款 | BALANCE + 预授权状态机 | `NONE|FROZEN|CAPTURED|RELEASED` |
| 充值 | WECHAT / ALIPAY | 回调幂等；dev mock |
| 未付订单补支付 | 订单 pay 接口 | 消费者「去支付」 |

---

## 4. API 与鉴权约定

### 4.1 响应与分页

- 统一：`ApiResponse<T>`，**`code=0` 成功**（`common-core`）
- 分页：`PageResult<T>(items, page, size, total)`
- 对外：`/api/v2/**`；服务间：`/internal/v1/**` + 头 `X-Internal-Api-Key`
- 网关：**拦截对外暴露 `/internal/`**（仅集群内互调）

### 4.2 鉴权分层

| 面 | 机制 |
|----|------|
| 消费者 / 运营 / 商户 | JWT Bearer（或 Cookie / 部分 GET `access_token`） |
| 运营写接口 | `@RequiresPermissions("ops:...")` + `PermissionAspect` |
| 商户写接口 | `@RequiresPermissions("merchant:...")` + 商户数据范围 |
| 内部 | `InternalApiAuthInterceptor`（可选 CIDR） |
| 视觉 HTTP | 同样内部 Key（`/api/v2/vision/*`） |

公开白名单（概念）：`/auth/**`、支付 **notify**、公告/媒体/部分营销等（以 `WebConfig` 为准）。

### 4.3 主要 API 面（记忆用）

| 前缀 | 代表 Controller | 用途 |
|------|-----------------|------|
| `/api/v2/auth` | `AuthController` | 登录/验证码/刷新/2FA |
| `/api/v2/sessions` | `SessionController` | 开门会话 |
| `/api/v2/orders` | `OrderController` | 订单/支付/退款/视频 |
| `/api/v2/disputes` | `ConsumerDisputeController` | 消费者争议 |
| `/api/v2/merchant` | `MerchantPortalController` | 商户门户（大） |
| `/api/v2/ops/admin` | `AdminDashboardController` + 按域 `Ops*Controller`（`OpsCommercialController` 仅留 commercial-flow） | 运营 |
| `/internal/v1/sessions/*` | `SessionInternalController` | 门事件/视频/重力/live-cart |
| `/internal/v1/devices/*` | trade 侧 | 心跳/存在性/OTA/库存快照 |
| device `/internal/v1/devices/{id}/open-door` | `DeviceInternalController` | 开门指令 |

更细合同见 [API_DOCUMENTATION.md](API_DOCUMENTATION.md)、[MERCHANT_PLATFORM.md](MERCHANT_PLATFORM.md)。

---

## 5. 数据层

- **唯一业务库**：PostgreSQL `localhost:15433/aicabinet`
- **迁移**：仅 Flyway `services/trade-service/src/main/resources/db/migration/V{n}__*.sql`（当前至 **V265**）
- **ORM**：MyBatis-Plus + `resources/mapper/*.xml`；多数业务主键为字符串输入型
- **主题簇**（按迁移命名频率）：RBAC/权限、商户钱包分账、会话订单支付、设备运维、仓储采购、营销会员、争议视觉、演示种子

**约定**：

- 改表只加新 Vn，不改已发布脚本
- 权限/菜单变更同步 RBAC 种子
- 字典只做展示文案；能力开关走 Java 常量 + 环境变量（见 MODULES.md）

---

## 6. 前端结构

### 6.1 运营后台 `clients/admin-vue`

- Vue 3 + Element Plus + Pinia + Vue Router（`base: /admin/`）
- 构建：`node scripts/build-admin.mjs` → `services/trade-service/src/main/resources/static/admin/`
- 菜单组：概览 / 交易履约 / 设备商品 / 履约仓储 / 财务商户 / 增长风控 / 系统
- 权限：`shared-rbac` + `v-hasPermi` + 路由守卫；菜单源 `config/menu.ts`

### 6.2 消费者 `clients/consumer-mp`

- uni-app；Tab：首页扫码、订单、我的
- H5 `:3002`（Playwright 主测面）；mp-weixin 需刷新 `dist`
- 关键工具：`utils/consumer-api.ts`、开门幂等 `getOrCreateOpenAttempt`、二维码 `shared-uni/qrcode`

### 6.3 商户 `clients/merchant-mp`

- uni-app；Tab：工作台、柜机、待办、我的
- H5 `:3001`；导航 = RBAC ∩ 功能包（field / biz / team）`config/merchant-nav.ts`

### 6.4 共享包

| 包 | 职责 |
|----|------|
| `shared-types` | 与后端对齐的 TS DTO |
| `shared-api` | 浏览器 `ApiClient`（超时、刷新、401） |
| `shared-dict` | 编译期 DICT + runtime overrides |
| `shared-rbac` | `matchPermission`、商户 pack |
| `shared-uni` | request / api-base / qrcode / status-bar / theme… |

**漂移风险**：`shared-types` ↔ `common-core` DTO；`shared-dict` ↔ `SysDictBootstrap` / `/dicts/runtime`。

---

## 7. 视觉与边缘

### 7.1 vision-service

- FastAPI；默认 **mock**（`MOCK_ENABLED=true`）
- 工厂：`mock` | `quectel`（占位未实现）| 多路融合 `fusion.py`
- DeepSeek：争议/类名建议辅助
- 生产方向：端侧识别上报 trade（见 [VISION_QUECTEL_INTEGRATION.md](VISION_QUECTEL_INTEGRATION.md)）；云端不做 YOLO
- trade 侧：`VisionServiceClient` + Resilience4j 熔断；可选 Kafka 异步（默认关）

### 7.2 edge

| 组件 | 作用 |
|------|------|
| `device-simulator` | MQTT 模拟柜；上传 testdata；HTTP 手动关门（full 栈常 `:18089`） |
| `android-app` | 真实柜机：收令→开锁→录像→MinIO→门事件；`mockDebug` / `deviceDebug` |

**同 deviceId 勿同时跑模拟器与真机。**

---

## 8. 基础设施与端口

| 组件 | 端口（IDEA 模式） | Docker full 常见 |
|------|-------------------|------------------|
| PostgreSQL | 15433 | 同 |
| Redis | 6379 | 同（进 trade health） |
| EMQX MQTT | 11883 | 同 |
| MinIO | 9000（Win 常 19000） | 同 |
| trade | 8080 | **18080** |
| device | 8081 | 18081 |
| vision | 8082 | 18082 |
| Gateway | 80 | 同 |
| Grafana | 13000 | 同 |
| XXL-JOB | — | 18090 |
| Prometheus | 9090 | 同 |

Compose 入口见 `infra/README.md`：`docker-compose.yml`（infra）/ `full.yml` / `win-ports.yml` / `production.yml` 等。

**健康依赖坑**：trade `/actuator/health` 含 Redis + vision 探针；vision 未起 → trade=DOWN（业务 HTTP 仍可能 200）。

---

## 9. 现有测试资产地图

### 9.1 后端

| 资产 | 位置 | 覆盖特点 |
|------|------|----------|
| 单元 + 并发 | `trade-service/src/test` ~202 | 锁/幂等/钱包/补货/支付回调强；**god service 分支不全** |
| E2E（Testcontainers） | `ConsumerE2ETest` / `MerchantE2ETest` / `AdminE2ETest` | MockMvc；开门常 mock |
| device-service | 仅 `DoorEventDeduplicatorTest`、`DeviceCommandTrackerTest` | **缺 MQTT 集成测** |
| common-core | 基本无独立单测 | DTO 靠编译约束 |

### 9.2 脚本 E2E（API 级，优先复用）

| 脚本 | 覆盖 |
|------|------|
| `verify-local.ps1` | 健康 + 购物；可选 Vision/补货/争议等开关 |
| `verify-full.ps1` | 编译 + admin 构建 + E2E |
| `e2e-shopping.ps1` | 开门→关门→结算（`WECHAT\|ALIPAY\|BALANCE`） |
| `e2e-dispute-recognition.ps1` | force need_review → 争议 |
| `e2e-vision-gravity-shopping.ps1` | 视觉+重力 |
| `e2e-replenishment.ps1` | 补货闭环 |
| `e2e-three-end.ps1` | 三端 API 矩阵 |
| `e2e-full-flow-milk.ps1` | 采购→仓→补货→购物→分账 |
| `e2e-fund-safety.ps1` | 资金安全/幂等 |
| `e2e-*-refund*.ps1` | 退款与库存回滚 |
| `e2e-demo-smoke.ps1` | 演示烟雾 |

### 9.3 UI（必须真实浏览器；优先 Playwright）

| 脚本 | 目标 |
|------|------|
| `clients/admin-vue/tests/admin-uat.mjs` | 运营后台 |
| `role-regression-uat.mjs` | 财务/补货员/只读角色 |
| `three-end-business-uat.mjs` | 三端业务 UI |
| `three-end-dispute-ui-uat.mjs` | 争议结案 UI |
| `consumer-mp/tests/consumer-h5-uat.mjs` | 消费者 H5 `:3002` |
| `merchant-mp/tests/merchant-h5-uat.mjs` | 商户 H5 `:3001` |

说明见 [BUSINESS_FULL_TEST_MATRIX.md](BUSINESS_FULL_TEST_MATRIX.md)（全页面+按钮+**L3 业务挂钩**/权限/字典/边界）、[BROWSER_MIN_UAT.md](BROWSER_MIN_UAT.md)、[BROWSER_FULL_UAT_PLAN.md](BROWSER_FULL_UAT_PLAN.md)。  
**禁止**仅用 curl/日志宣称 UI 通过。

### 9.4 演示账号（dev）

| 角色 | 账号 | 密码/验证码 |
|------|------|-------------|
| 运营超管 | `13900000001` | `123456` + 图形验证码 |
| 消费者 | `13800138000` | SMS `123456` |
| 商户 | `13800138001` | `123456` |
| 演示柜 | `CAB-001` | — |

完整矩阵：[DEMO_ACCOUNTS.md](DEMO_ACCOUNTS.md)。

### 9.5 CI

- `.github/workflows/ci.yml`：密钥扫描、Maven verify、vision pytest、admin 静态 diff、pnpm lint/typecheck/H5
- `sonar.yml`：自托管 Jacoco + Quality Gate

---

## 10. 复杂度热点（优化 / 测试优先队列）

按「出错代价 × 变更频率 × 可测性缺口」排序：

| 优先级 | 热点 | 路径 | 建议 |
|--------|------|------|------|
| P0 | 会话生命周期 + 锁 | `SessionService.java` | 决策表单测：开门竞态、门事件乱序、取消 |
| P0 | 结算 / 争议分支 | `SettlementService.java` | 置信度/映射失败/熔断/重力 → 矩阵测试 |
| P0 | 扣款 / 预授权 | `OrderPaymentService` / `ConsumerPreauthService` | 与 `e2e-fund-safety` 对齐；回调幂等 |
| P0 | 支付回调 | `WeChatPayNotifyService` / `PaymentService` | 重复通知、乱序 |
| P1 | 争议结案退款 | `DisputeService.java` | 三端 UI + API 双轨 |
| P1 | 库存 / 货道 | `InventoryService` / `DeviceSlotService` | 并发扣减 + 退款回滚 |
| P1 | 钱包 / 提现 | `MerchantWalletService` / `MerchantWithdrawService` | 已有并发测，补打款失败回滚 |
| P1 | MQTT 桥 | `MqttEventListener` / `TradeServiceClient` | **补集成测**；与模拟器契约 |
| P2 | 运营巨石（已拆 Controller / Facade） | `AdminDashboardService`（门面+已抽 Query/Admin Service）+ `Ops*Controller` / `Ops*AdminService` | 落地见 [PASS_3F_OPS_GOD_CLASSES.md](pass-notes/PASS_3F_OPS_GOD_CLASSES.md)；`OpsCommercialFacade` 已删 |
| P2 | 商户巨石（已抽切片） | `MerchantPortalService`（门面）+ Workbench/Device/Inventory/Team/Finance | 同上 |
| P2 | Flyway 膨胀 | `db/migration` | 种子与 schema 分离；避免测库依赖过重 seed |
| P3 | Admin 包体 / 列表 | Vite 懒加载、表格 composable | 大列表虚拟化、CSV 权限码 |
| P3 | 小程序 dist 新鲜度 | `dev:mp-weixin` watcher | 验收前核对 dist mtime |

**God 类体量（优化候选）**：`AdminDashboardService`、`SessionService`、`DisputeService`、`SettlementService`、`MerchantPortalService`、`MerchantPortalController` 仍偏大（改动需配套回归）。**OpsCommercial**：Controller 已按域拆完、Facade 已删，不再列为运营 API 巨石。

---

## 11. 推荐验证矩阵（以后每次改动按触发条件裁剪）

### 11.1 纯后端（无 UI）

1. 相关 `*Test` / `*ConcurrencyTest`
2. `.\scripts\verify-local.ps1`（模式匹配的 BaseUrl）
3. 触及金钱路径时加：`e2e-shopping` + `e2e-fund-safety` 或对应 refund 脚本

### 11.2 触及购物 / 视觉 / 门

1. 上节 + `e2e-dispute-recognition` 或 `e2e-vision-gravity-shopping`
2. 模拟器 `CAB-001` 心跳在线
3. vision `:8082/health` UP

### 11.3 触及 Admin / H5 可见面

1. `node scripts/build-admin.mjs`（若改 admin）
2. Playwright：对应 `*-uat.mjs`（headed 优先）
3. 记录实际看到的页面结果（禁止口头「应该好了」）

### 11.4 触及三端争议 / 订单视频

1. `create-open-dispute.ps1`（如需要）
2. `three-end-business-uat.mjs` / `three-end-dispute-ui-uat.mjs`

### 11.5 发布门禁

1. `verify-full.ps1` 或 CI 等价
2. `verify-production-readiness.ps1`
3. 确认 mock/captcha/密钥未带入生产包（`validate-miniapp-env.mjs` 等）

---

## 12. 优化方向（有证据后再动手）

| 方向 | 现状 | 建议切入点 |
|------|------|------------|
| 正确性 | 并发测多，MQTT/视觉集成薄 | 先补「模拟器↔device↔trade」契约测 |
| 可维护性 | 巨石 Service/Controller | 按域（结算/争议/商户门户）垂直切片，先测后拆 |
| 性能 | 有 [PERFORMANCE_TESTING.md](PERFORMANCE_TESTING.md)、[CLUSTER_READINESS.md](CLUSTER_READINESS.md) | 压测聚焦：开门锁、结算、库存；建立基线数字入库 |
| 可观测性 | Actuator + Prometheus/Grafana | 关键业务指标：开门成功率、结算时长、争议率、MQTT 转发失败 |
| 视觉 | 云端 mock；端侧待对接 | 优化争议辅助与映射质量，而非恢复云端 YOLO |
| 前端 | UAT 脚本散落、Vitest 极少 | 固化 Playwright 项目配置；Admin 关键工具函数补单测 |
| 文档 | 多而部分过期 | **以本文 + STARTUP_REFERENCE 为真源**；归档 ANALYSIS_* 仅作历史 |

---

## 13. 「必知」文件索引

### 后端

1. `common-core/.../enums/SessionState.java`
2. `trade/.../service/SessionService.java`
3. `trade/.../service/SettlementService.java`
4. `trade/.../service/OrderPaymentService.java` + `ConsumerPreauthService.java`
5. `trade/.../service/DistributedLockService.java`
6. `trade/.../client/DeviceServiceClient.java` + `VisionServiceClient.java`
7. `trade/.../api/SessionController.java` + `SessionInternalController.java`
8. `trade/.../config/WebConfig.java` + `auth/AuthInterceptor` + `PermissionAspect`
9. `trade/.../service/DisputeService.java`
10. `trade/.../service/MerchantWalletService.java` / `MerchantWithdrawService` / `RevenueSplitService`
11. `device/.../mqtt/MqttEventListener.java` + `DeviceCommandService.java`
12. `common-core/.../dto/ApiResponse.java` + `PageResult`

### 前端

13. `clients/admin-vue/src/router/index.ts` + `config/menu.ts`
14. `clients/admin-vue/src/api/client.ts`
15. `clients/consumer-mp/src/pages/index/index.vue` + `utils/consumer-api.ts`
16. `clients/merchant-mp/src/config/merchant-nav.ts` + `utils/merchant-api.ts`
17. `packages/shared-{types,api,dict,rbac,uni}/`

### 视觉 / 边缘 / 脚本

18. `vision-service/app/main.py` + `recognition/factory.py`
19. `edge/android-app/README.md`
20. `scripts/e2e-lib.ps1` + `verify-local.ps1` + `build-admin.mjs`

---

## 14. 相关文档导航

| 需求 | 文档 |
|------|------|
| 今天怎么起服务 | [STARTUP_REFERENCE.md](STARTUP_REFERENCE.md) / [LOCAL_SETUP.md](LOCAL_SETUP.md) |
| 模块路径 | [MODULES.md](MODULES.md) |
| 架构边界 | [ARCHITECTURE.md](ARCHITECTURE.md) |
| 账号 | [DEMO_ACCOUNTS.md](DEMO_ACCOUNTS.md) |
| UI 全量节点矩阵 | [BUSINESS_FULL_TEST_MATRIX.md](BUSINESS_FULL_TEST_MATRIX.md) |
| UI UAT（抽样） | [BROWSER_MIN_UAT.md](BROWSER_MIN_UAT.md) |
| 上线阻塞 | [GO_LIVE_EXECUTION_PLAN.md](GO_LIVE_EXECUTION_PLAN.md) / [CODE_FIX_CHECKLIST.md](CODE_FIX_CHECKLIST.md) |
| 生产 | [PRODUCTION.md](PRODUCTION.md) |
| 端侧视觉 | [VISION_QUECTEL_INTEGRATION.md](VISION_QUECTEL_INTEGRATION.md) |
| DevOps | [DEVOPS.md](DEVOPS.md) |
| 历史端分析 | `docs/archive/ANALYSIS_*.md`（可能过时，以代码与本文为准） |

---

## 15. 维护约定

1. **改核心链路（会话/结算/支付/库存/MQTT）**：同步更新本文 §3、§10、§11。
2. **新增 Flyway 大主题或新服务边界**：更新 §1、§5。
3. **新增 E2E/UAT 脚本**：登记到 §9。
4. **发现文档与代码冲突**：以代码 + 本轮实测为准，并改文档；不要静默分叉。
5. **2026-09 清理**：已删除过时云端 YOLO 采集/校验脚本与 `OPEN_CABINET_DATA_COLLECTION.md`；点状验收报告迁入 `docs/archive/`。索引见 [README_DOCS.md](README_DOCS.md)。

---

*本文是后续测试用例树、性能基线、重构里程碑的共同前提，而不是一次性审计报告。*
