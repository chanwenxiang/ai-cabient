# 设计文档索引

完整架构设计见对话记录；本文档为项目内快速参考。模块路径与启动方式见 [MODULES.md](MODULES.md)。

## 与旧系统关系

```
demo/
├── easygo/ego-automat/     ← 旧系统，只读参考，不修改
├── ego-automat-android/    ← 旧设备端，只读参考
└── ai-cabinet/             ← 新系统（本项目）
```

## 核心改进

| 旧 (ego-automat) | 新 (ai-cabinet) |
|------------------|-----------------|
| 重力传感器识别 | AI 视觉识别 + 可选重力融合 |
| Socket.IO (M8) | MQTT 5.0 统一协议 |
| m8_door_current_status | shopping_session 状态机 |
| Spring Boot 1.2 / Java 8 | Spring Boot 3.2 / Java 17 |
| 29 模块单体 | 按域拆分微服务 |
| 商户 Web + 原生小程序 | 消费者与商户独立微信小程序 |

## 服务边界

- **trade-service**：购物会话、订单、结算、运营 / 商户门户 API
- **device-service**：设备指令、MQTT、状态、心跳
- **vision-service**：AI 识别（Python FastAPI）
- **edge/android-app**：柜机端 Android App
- **clients/admin-vue**：运营控制台（静态资源挂载于 trade-service）
- **clients/consumer-mp**：消费者扫码、开门购物、订单与售后
- **clients/merchant-mp**：商户经营、柜机、定价与待办

## 识别链路

生产：**端侧识别提供方**（移远 OpenVending 等）在柜机完成推理，将 SKU 清单上报 trade-service 结算；平台维护「端侧类名 → 商品」映射表（`sku_vision_mapping`）。

开发联调：关门视频 → MinIO → **vision-service**（mock 识别，默认 `MOCK_ENABLED=true`）→ SKU 映射 → trade-service 结算。

| 模式 | 环境变量 | 行为 |
|------|----------|------|
| 开发 mock | `MOCK_ENABLED=true`（默认） | 返回 mock SKU，便于全栈联调 |
| 端侧提供方 | 端侧 SDK 上报 `POST /internal/v1/vision/...` | 置信度达标直接结算，低置信进争议 |
| 关闭 mock | `VISION_FORCE_REAL=true` 或 `MOCK_ENABLED=false` | 无端侧结果时进人工审核，不静默 mock |

可选 **重力传感器融合**：视觉与重力 SKU 数量不一致时强制 `need_review`（`GravitySettlementHelper`）。

端侧对接方案见 [`VISION_QUECTEL_INTEGRATION.md`](VISION_QUECTEL_INTEGRATION.md)。

## 商业落地要点

| 能力 | 说明 |
|------|------|
| 对象存储 | MinIO（本地）/ OSS（生产）存关门视频 |
| 识别链路 | 端侧识别提供方 + SKU 类名映射表；dev 用 vision-service mock |
| 支付 | 微信 V3；dev mock 分支与 prod 共用代码 |
| 短信 | Webhook 模式；预发可用 `sms-webhook-mock` |
| 多租户 | 商户数据隔离 via `MerchantScopeService` |
| 运营能力 | OTA、风控、对账、补货、RBAC（admin-vue）；部门与审批见 [APPROVAL_DEPARTMENT_FLOW.md](APPROVAL_DEPARTMENT_FLOW.md)；与若依对比见 [RBAC_VS_RUOYI.md](RBAC_VS_RUOYI.md) |

## API 版本

- 旧：`/m8/v1/*` — 不迁移
- 新：`/api/v2/*` — 本项目管理

## 环境要求

- JDK 17+
- Docker（PostgreSQL、Redis、EMQX、MinIO）
- Python 3.10+（vision-service）
- Node 24.18+（admin-vue / uni-app 开发）

## 前端产品决策

商户 Web 与旧原生小程序已废弃，统一为 **uni-app 移动客户端**。详见 [FRONTEND_PRODUCT_DECISIONS.md](FRONTEND_PRODUCT_DECISIONS.md)。

## 部署

生产 checklist、环境变量与镜像构建见 [PRODUCTION.md](PRODUCTION.md)。
