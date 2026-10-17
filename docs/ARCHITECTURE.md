# 设计文档索引

完整架构设计见对话记录；本文档为项目内快速参考。模块路径与启动方式见 [MODULES.md](MODULES.md)。测试与优化底稿见 [CODEBASE_FOUNDATION.md](CODEBASE_FOUNDATION.md)。

## 与旧系统关系

> 🔴 **2026-10-06 17:35 二次更正（推翻同日上午的「已不在本机」结论）**
>
> 旧仓**一直在本机且完整可读**：`D:\ideaCode\easygo\`（含 `ego-automat/`、`mis-server/`、
> `ego-automat-operation/`、`m9managepro/`、`dvm/`、`dinngdang-wx/` 等触点）。
>
> 同日上午的结论「旧仓已随 2026-10-04 搬迁而不可用」是**错的** ——
> 当年只查了 `D:\ai-generated code\` 下的目录（那里确实只有 4 个 ai-cabinet 副本），
> **没查 `D:\ideaCode\`**。据此得出的两条连带结论也一并作废：
> ①「查旧系统实现须先获取代码副本」；②审计报告里「旧仓不存在，无法取证」的判断。
>
> ⇒ **查旧系统实现时请直接读 `D:\ideaCode\easygo`。** 若该路径失效，
> 用 `ls -d /d/ideaCode/easygo` 确认后再下「不可用」结论 —— **不要凭印象说某目录不存在**。
>
> 本仓**不依赖旧仓代码**（业务与硬件协议仅为参考），旧系统对照结论已沉淀在
> [SYSTEM_COMPARISON_EASYGO_VS_AICABINET_2026-10-01.md](SYSTEM_COMPARISON_EASYGO_VS_AICABINET_2026-10-01.md)，
> 该文档中引用的 `文件:行号` 证据在旧仓可复核（2026-10-06 已抽查提现/账单/分账三处，全部吻合）。

```
D:\ideaCode\easygo\                    # 旧系统仓（本机可读，只读参考）
├── ego-automat/                       # 弹簧柜主系统 + 老管理后台 + 运营员 App 后端
├── mis-server/ mis-web/               # 新一代运营后台（RuoYi-Vue-Plus）
├── ego-automat-operation/             # 运营员/补货员 App 后端（含提现四渠道实现）
├── m9managepro/                       # M9 生态 web 后台（品牌商/总监/经理/店长）
├── dvm/ dinngdang-wx/                 # dvm 线（信箱柜/公益/信义卡/彩票/加盟）消费者端
└── feishu-*/                          # 飞书侧工具（采购申请、报表、任务奖励）

D:\ai-generated code\ai-cabinet\       # 新系统（本项目）
```

## 核心改进

| 旧 (ego-automat) | 新 (ai-cabinet) |
|------------------|-----------------|
| 重力传感器识别 | **将邑端侧视觉识别**（柜机 rknn 推理，云端无识别服务） |
| Socket.IO (M8) | MQTT 5.0 统一协议 |
| m8_door_current_status | shopping_session 状态机 |
| Spring Boot 1.2 / Java 8 | Spring Boot 3.2 / Java 17 |
| 29 模块单体 | 按域拆分微服务 |
| 商户 Web + 原生小程序 | 消费者与商户独立微信小程序 |

## 服务边界

- **trade-service**：购物会话、订单、结算、运营 / 商户门户 API
- **device-service**：设备指令、MQTT、状态、心跳
- **jiangyi-gateway**：将邑柜接入网关（设备 token / WS / 上报归一化）——生产柜机即经此接入
- **edge/device-simulator**：MQTT 边缘侧的联调 / CI 替身（旧模式 + `JiangyiDeviceSimulator`）
- 自研柜体固件 `edge/android-app`（Android App）已于 2026-10-18 随「生产仅将邑套件」退役，可从 git history 恢复
- **clients/admin-vue**：运营控制台（静态资源挂载于 trade-service）
- **clients/consumer-mp**：消费者扫码、开门购物、订单与售后
- **clients/merchant-mp**：商户经营、柜机、定价与待办

## 识别链路（将邑端侧单轨 · 2026-10-11）

识别只在**柜机端**完成：将邑采集商品样本 → 将邑云端训练 → 模型（rknn）+ 类目文件下发柜机 → 开门购物时柜机本地推理 → `classId+quantity` 经 jiangyi-gateway 归一化上报 → 映射到 SKU（`jiangyi_class_mapping` / `sku_jiangyi_link`）→ trade-service 结算。**商品图片不经过我方服务器，云端无识别服务**（vision-service 已退役）。

| 环节 | 位置 | 说明 |
|------|------|------|
| 上报归一 | gateway `/jiangyi/api/orderProduct/addRecognitionGoodsToOrder` | classId 未命中映射 fail-closed 转 DISPUTED |
| 结果受理 | trade `/internal/v1/vision/edge-results` | 复用既有结算管道（RECOGNIZING→SETTLING） |
| 金额口径 | Σ(映射 SKU 现价 × qty) | 不采信设备侧金额 |
| 低置信/未命中 | DISPUTED 争议单 | 先扣款冻结，人工审核 |

本地演示（mock 环境）：开门关门不带识别 → gravity/购物车证据短路结算，与识别引擎无关。全链路设计见 [`JIANGYI_GATEWAY_DESIGN.md`](JIANGYI_GATEWAY_DESIGN.md)。

## 商业落地要点

| 能力 | 说明 |
|------|------|
| 对象存储 | MinIO（本地）/ OSS（生产）存关门视频 |
| 识别链路 | 将邑端侧识别 + classId 映射（`jiangyi_class_mapping`） |
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
- Node 24.18+（admin-vue / uni-app 开发）

## 前端产品决策

商户 Web 与旧原生小程序已废弃，统一为 **uni-app 移动客户端**。详见 [FRONTEND_PRODUCT_DECISIONS.md](FRONTEND_PRODUCT_DECISIONS.md)。

## 部署

生产 checklist、环境变量与镜像构建见 [PRODUCTION.md](PRODUCTION.md)。
