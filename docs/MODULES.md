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
| `packages/shared-dict` | 共享前端包 | 字典 / 枚举文案 | 同上 |
| `packages/shared-uni` | 共享 uni 工具 | 二维码解析、会话状态、格式化 | 被 consumer-mp / merchant-mp 引用 |

## 后端服务

| 路径 | 中文名 | 说明 | 启动 |
|------|--------|------|------|
| `services/trade-service` | 交易服务 | 会话、订单、结算、运营 / 商户 API | IDEA Run `TradeServiceApplication` 或 `mvn spring-boot:run`（:8080） |
| `services/device-service` | 设备服务 | MQTT、设备状态、指令下发 | `DeviceServiceApplication`（:8081） |
| `services/common/common-core` | 公共核心库 | DTO、枚举、工具 | Maven 依赖，不单独启动 |
| `vision-service` | 视觉识别服务 | FastAPI + YOLO 识别 | `uvicorn app.main:app --port 8082` |

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
| `scripts/start-infra.ps1` | 启动 Docker 基础设施 |
| `scripts/start-local.ps1` | 本地启动 Java / Python 服务 |
| `scripts/stop-apps.ps1` | 停止应用进程 |
| `scripts/check-ports.ps1` | 端口占用检查 |
| `scripts/seed-demo-data.ps1` | Demo 种子数据 |
| `scripts/verify-local.ps1` | 本地健康 + 购物 E2E |
| `scripts/verify-full.ps1` | 编译 + admin-vue 构建 + E2E |
| `scripts/verify-production-readiness.ps1` | 上线前门禁 |
| `scripts/deploy-production.ps1` | 生产 env 检查清单 |
| `scripts/deploy-staging.ps1` | 预发 compose 部署 |
| `scripts/run-api-tests.ps1` | API 冒烟 |
| `scripts/e2e-shopping.ps1` | 核心购物流程 E2E |
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
| [VISION_YOLO_TEST.md](VISION_YOLO_TEST.md) | YOLO 识别测试 |

## 与旧系统关系

```
demo/
├── easygo/ego-automat/     ← 旧系统，只读参考
├── ego-automat-android/    ← 旧设备端，只读参考
└── ai-cabinet/             ← 本项目
```

数据库 schema 由 Flyway 管理：`services/trade-service/src/main/resources/db/migration/`。本地联调无需额外 ETL。

小程序生产构建前，复制对应客户端的 `.env.production.example` 为 `.env.production.local`，并把
`VITE_API_BASE_URL` 设置为已加入微信小程序合法域名列表的真实 HTTPS API 地址。构建脚本会拒绝
localhost、示例域名和占位域名，避免把开发地址打入生产包。

