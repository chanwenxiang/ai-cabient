# 模块索引

本文档列出仓库各模块的**路径（不变）**、中文产品名、职责与启动方式。详细联调见 [LOCAL_SETUP.md](LOCAL_SETUP.md)，生产部署见 [PRODUCTION.md](PRODUCTION.md)。

## 前端

| 路径 | 中文名 | 说明 | 启动 / 构建 |
|------|--------|------|-------------|
| `clients/admin-vue` | 运营控制台 | Vue3 + Element Plus，构建到 trade-service `/admin` | `cd clients/admin-vue && npm run dev` / `npm run build` |
| `clients/consumer-mp` | 消费者小程序 | 独立 uni-app 微信小程序，扫码开门购物 | `cd clients/consumer-mp && npm run dev:mp-weixin` / `npm run build:mp-weixin` |
| `clients/merchant-mp` | 商户小程序 | 独立 uni-app 微信小程序，柜机运营 / 定价 / 待办 | `cd clients/merchant-mp && npm run dev:mp-weixin` / `npm run build:mp-weixin` |
| `packages/shared-types` | 共享前端包 | TypeScript 类型 | 被 admin-vue / 小程序 Vite alias 引用 |
| `packages/shared-api` | 共享前端包 | API 客户端封装 | 同上 |
| `packages/shared-dict` | 共享前端包 | 字典 / 枚举**展示文案**（非能力开关） | 同上；登录后可被 `/api/v2/dicts/runtime` 覆盖 |
| `packages/shared-uni` | 共享 uni 工具 | 二维码解析、会话状态、格式化 | 被 consumer-mp / merchant-mp 引用 |

## 后端服务

| 路径 | 中文名 | 说明 | 启动 |
|------|--------|------|------|
| `services/trade-service` | 交易服务 | 会话、订单、结算、运营 / 商户 API | IDEA Run `TradeServiceApplication` 或 `mvn spring-boot:run`（:8080） |
| `services/device-service` | 设备服务 | MQTT、设备状态、指令下发 | `DeviceServiceApplication`（:8081） |
| `services/common/common-core` | 公共核心库 | DTO、枚举、工具 | Maven 依赖，不单独启动 |
| `vision-service` | 视觉 mock / 争议辅助 | FastAPI（无端侧模型） | `uvicorn app.main:app --port 8082` |

## 边缘与联调

| 路径 | 中文名 | 说明 | 启动 |
|------|--------|------|------|
| `edge/device-simulator` | 设备模拟器 | 本地 MQTT 开门 / 视频上传联调 | `DeviceSimulator`，参数 `CAB-001` |
| `edge/android-app` | 柜机端 App | Android 工控端 | Android Studio，`mockDebug` / `deviceDebug` |

## 基础设施

| 路径 | 中文名 | 说明 |
|------|--------|------|
| `infra/` | 基础设施 | Docker Compose、网关、环境变量模板 |
| `infra/docker/` | 容器镜像 | `trade-service`、`device-service`、`vision-service` Dockerfile |
| `proto/` | 协议定义 | MQTT / 内部通信 Protobuf |

## 脚本（核心）

| 脚本 | 用途 |
|------|------|
| `docker-up.ps1`（仓库根） | 启动 Docker 全栈（`ai-cabinet` 项目） |
| `scripts/start-infra.ps1` | **已转发**到 `docker-up.ps1`（勿单独起 infra 项目） |
| `scripts/start-local.ps1` | 本地启动 Java / Python 服务 |
| `scripts/stop-apps.ps1` | 停止应用进程 |
| `scripts/check-ports.ps1` | 端口占用检查 |
| `scripts/seed-demo-data.ps1` | Demo 种子数据 |
| `scripts/verify-local.ps1` | 本地健康 + 购物 E2E；可选 `-WithReplenishment/-WithAlipay/-WithPayscore/-WithDispute`（默认 BaseUrl=`http://localhost:18080`，可用 `E2E_BASE_URL` 覆盖） |
| `scripts/verify-full.ps1` | 编译 + admin-vue 构建 + E2E |
| `scripts/verify-production-readiness.ps1` | 上线前门禁 |
| `scripts/deploy-production.ps1` | 生产 env 检查清单 |
| `scripts/deploy-staging.ps1` | 预发 compose 部署 |
| `scripts/run-api-tests.ps1` | API 冒烟 |
| `scripts/e2e-shopping.ps1` | 核心购物流程 E2E；可选 `-Channel WECHAT\|ALIPAY\|BALANCE` |
| `scripts/e2e-replenishment.ps1` | 补货闭环 E2E（计划→仓配可选→签到开门→完成） |
| `scripts/check-env.ps1` | 环境变量必填项检查 |
| `scripts/sms-webhook-mock.py` | 预发 SMS mock |

## 文档

| 文档 | 内容 |
|------|------|
| [README.md](../README.md) | 项目总览 |
| [LOCAL_SETUP.md](LOCAL_SETUP.md) | 本地联调 |
| [STARTUP_REFERENCE.md](STARTUP_REFERENCE.md) | 端口 / 账号速查 |
| [PRODUCTION.md](PRODUCTION.md) | 生产部署 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 架构速查 |
| [FRONTEND_PRODUCT_DECISIONS.md](FRONTEND_PRODUCT_DECISIONS.md) | 前端产品决策 |
| [MERCHANT_PLATFORM.md](MERCHANT_PLATFORM.md) | 商户 API / 账号 |
| [VISION_QUECTEL_INTEGRATION.md](VISION_QUECTEL_INTEGRATION.md) | 端侧识别对接 |

## 与旧系统关系

```
demo/
├── easygo/ego-automat/     ← 旧系统，只读参考
├── ego-automat-android/    ← 旧设备端，只读参考
└── ai-cabinet/             ← 本项目
```

数据库 schema 由 Flyway 管理：`services/trade-service/src/main/resources/db/migration/`。本地联调无需额外 ETL。

### 字典用法约定

- **字典只做展示**：状态 / 渠道 / 异常类型等 `value → label`、筛选项与 Tag；运营改文案或启停筛选项不影响扣款与开门。
- **能力开关不进字典**：支付是否可用由 Java 常量（如 `PayChannels`）与环境变量（`ALIPAY_ENABLED`、`PAYSCORE_*` 等）决定。
- **多端对齐**：已登录客户端拉 `GET /api/v2/dicts/runtime`（ACTIVE 项）写入 `shared-dict` overrides；失败回退编译期 `DICT`。管理写接口仍走 `/api/v2/ops/admin/dicts`。
- **新增枚举**：先改后端契约 / 状态机，再 seed `packages/shared-dict` 与 `SysDictBootstrap`。

### 权限（三端 + 若依 / AOP）

最终目标：运营后台、补货员（商户端）、消费者能力均可控，但机制不同。

| 受众 | 机制 | 怎么改权限 |
|------|------|-----------|
| 运营后台 | 若依式 M/C/F + 角色勾选；前端 `v-hasPermi` / `auth.hasPerm`；后端 `@RequiresPermissions`（AOP）；部门为组织+审批指派，交易数据范围仍用商户/设备（见 [RBAC_VS_RUOYI.md](RBAC_VS_RUOYI.md)、[APPROVAL_DEPARTMENT_FLOW.md](APPROVAL_DEPARTMENT_FLOW.md)） | 角色管理勾选按钮码；部门管理维护树与成员；新接口加注解 |
| **补货员 / 商户** | 同一 RBAC 表的 `merchant:*` 码 + 商户范围；API 注解如 `merchant:replenishment:view` | 运营给账号分配商户角色/权限；前端 `hasPerm(me, code)` |
| **消费者** | 非菜单 RBAC：登录态、实名/风控黑名单、支付渠道能力（env + `PayChannels`） | 后台用户/风控操作；部署环境开关；不按「按钮权限树」建模 |

**AOP 约定（推荐主路径）**

- 注解：`@RequiresPermissions("ops:xxx")` 或 `value={...}, logical=OR|AND`
- 切面：[`PermissionAspect`](../services/trade-service/src/main/java/com/aicabinet/trade/auth/PermissionAspect.java) 读取登录 `userId`，走 `PermissionService`（含若依分段通配与 `ops:admin`）
- **增删改权限控制**：优先改 Controller 注解；Service 内 `requirePermission` 可保留作内部调用双保险
- 前端按钮：`v-hasPermi="['ops:xxx']"`，与后端同码
- **导入 / 导出**：独立 F 码 `ops:{module}:export` / `ops:{module}:import`（见 `V106__export_import_button_perms.sql`）；已有特例 `ops:order:export`、`ops:coupon:export`、`ops:operlog:export`。CSV 的 `canImport` 仅表示有 handler，**不是**权限。
- **商户端**：页面进入用 `merchant:*:list|view`；写操作用 `:edit|:request|:reply`；导出用 `merchant:settlements:export` / `merchant:reports:export`。
- **消费者**：本次补全不含按钮 RBAC（见上表）。

**通配**：`ops:rbac:role:*` 覆盖 `ops:rbac:role:add`；任意无关 `*:*` 不再全局放行。

小程序生产构建前，复制对应客户端的 `.env.production.example` 为 `.env.production.local`，并把
`VITE_API_BASE_URL` 设置为已加入微信小程序合法域名列表的真实 HTTPS API 地址。构建脚本会拒绝
localhost、示例域名和占位域名，避免把开发地址打入生产包。

## 增长运营模块（2026-08 新增）

### 会员积分闭环
- 消费返积分（按会员等级 `points_rate`，有效期 365 天）、积分兑换优惠券、积分明细
- 定时任务：到期前提醒（`points-expiry`）、到期结转至过期并写 `EXPIRE` 日志；同一订单幂等返积分
- 运营后台：积分兑换管理（`/points-redeem`）、会员等级规则（`/member-levels`，可配门槛/积分区间/倍率/启停）
- 消费者端：积分明细 / 积分兑换页（`pages/points/*`），会员中心展示可用积分

### 消息触达
- 通知模板 + 日志（`notification_template` / `notification_log`），渠道按模板路由：站内信 / 微信订阅消息 / 短信
- 事件接入：订单支付、充值到账、补货任务指派、优惠券临期提醒、积分到期提醒、沉睡召回、结算到账
- 消费者消息中心（`pages/messages/messages`）：未读数、已读、按业务跳转、通知偏好开关、微信订阅授权引导
- 商户消息中心（`pages/messages/messages`）：补货任务跳转
- 真实渠道开关（默认关闭，未配置自动回退站内信）：
  - `NOTIFY_WECHAT_ENABLED=true` + `WECHAT_MINIAPP_ENABLED=true` + `WECHAT_CONSUMER_SUBSCRIBE_TEMPLATE=<模板ID>`
  - `NOTIFY_SMS_ENABLED=true` + `AICABINET_AUTH_SMS_WEBHOOK_URL=<短信 webhook>`（预发可用 `scripts/sms-webhook-mock.py`）

### 选品诊断与采购联动
- 运营后台选品诊断（`/sku-review`）：近 7/30/90 天动销诊断，支持建议下架 / 保留 / 确认下架（含替换 SKU）
- 采购建议自动排除「建议下架 / 已下架」商品（`PurchaseSuggestionService`）

### 用户分析与沉睡召回
- 用户分析（`/user-analysis`）：活跃 / 新增 / 复购 / 沉睡 / 客单价，含复购 TOP10 与沉睡名单导出
- 沉睡用户一键召回（`/user-recall`）：定向发券 + 召回通知，单次上限 1000 人（需 `ops:coupon:create`）

### 活动效果分析与补货员效率
- 活动效果分析（`/marketing-roi`）：发券 / 核销 / 核销率 / 核销面额 / 带动订单与营收
- 补货员效率（`/replenishment-staff`）：任务量 / 完成率 / 平均耗时 / 日均任务

### 数据一致性扩展与日志治理
- `DataConsistencyService` 新增 `POINTS_BALANCE`、`COUPON_ISSUED` 巡检项
- 定时归档（`growth-log-archive`，每日 03:00）：通知日志保留 6 个月、积分日志保留 12 个月（后台参数 `ops.log_retention.*`，0=不清理）

### 相关迁移与权限
- 迁移：`V163`（重建积分表与列/通知表/选品评审表/兑换目录/权限）、`V165`（过期与召回字段/模板）、`V166`（通知偏好/积分幂等索引）、`V167`（活动效果分析权限）、`V168`（通知渠道/等级规则权限；注：既有 `V164` 为文件附件 sha，勿占用该号）
- 新权限码：`ops:points:list/edit`、`ops:sku-review:list/edit`、`ops:user-analysis:view`、`ops:notify:list`、`ops:member-level:list/edit`、`ops:marketing-roi:view`
- 新单测：`PointsRedeemServiceTest`、`NotificationServiceTest`、`SkuDelistReviewServiceTest`、`UserBehaviorAnalyticsServiceTest`、`MarketingRoiServiceTest`、`ReplenishmentStaffReportServiceTest`（15 用例）

