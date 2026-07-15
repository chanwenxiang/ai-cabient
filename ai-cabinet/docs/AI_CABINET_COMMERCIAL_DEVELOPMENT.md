# 规模化 AI 开门柜开发文档

本文面向研发、产品、运维、交付和运营团队，描述 `ai-cabinet` 从当前工程实现演进为可规模化商用平台的开发规范。本文不是市场方案，而是可拆解任务、可验收、可上线的工程说明。

相关文档：

- [COMMERCIAL_ARCHITECTURE.md](COMMERCIAL_ARCHITECTURE.md)：非自研 CV 的商业识别方案
- [OPS_COMMERCIAL.md](OPS_COMMERCIAL.md)：OTA、风控、对账、补货、SLA、RBAC
- [PRODUCTION.md](PRODUCTION.md)：生产环境部署与安全清单
- [WAREHOUSE_TO_CABINET_FLOW.md](WAREHOUSE_TO_CABINET_FLOW.md)：仓库到柜机全流程
- [TEST_CASES.md](TEST_CASES.md)：测试用例集

---

## 1. 项目定位与商业目标

### 1.1 产品定位

AI 开门柜是面向办公室、园区、学校、医院、工厂、社区等点位的无人零售系统。用户扫码开门，取走商品后关门，系统通过视觉识别、可选重力传感器和库存台账生成订单并自动扣款。

规模化平台必须支撑：

| 能力 | 要求 |
|------|------|
| 多商户 | 不同商户独立设备、商品、收入、分账和运营权限 |
| 多城市/区域 | 设备按城市、区域、点位、路线运营 |
| 多设备 | 上千台柜机持续在线、心跳、OTA、告警和补货 |
| 多角色 | 消费者、补货员、运营、财务、商户、管理员 |
| 全链路闭环 | 商品建档、仓库入库、出库、补货、销售、库存扣减、对账、分账 |
| 可追溯 | 订单、识别结果、视频、批次、库存变动、运营操作均可追踪 |
| 可运维 | 生产 profile、日志、监控、备份、告警、故障演练 |

### 1.2 商业成功指标

上线后重点监控：

| 指标 | 目标口径 |
|------|----------|
| 开门成功率 | 成功进入购物会话 / 创建会话数 |
| 识别成功率 | 自动结算订单 / 关门识别会话 |
| 争议率 | 争议订单 / 总订单 |
| 平均结算耗时 | 关门到订单完成耗时，关注 P95 |
| 设备在线率 | 在线设备 / 已投放设备 |
| 库存准确率 | 账面库存与盘点库存一致率 |
| 补货履约率 | 按计划完成任务 / 计划任务 |
| 对账差异率 | 平台账与支付渠道账差异金额 / 总交易额 |

---

## 2. 系统总体架构

### 2.1 服务拓扑

```text
用户小程序 / 补货小程序
        |
        v
API Gateway / Nginx
        |
        +--------------------+
        |                    |
        v                    v
 trade-service        运营后台 admin
        |
        +--> PostgreSQL / Redis
        +--> MinIO 或 OSS
        +--> Kafka / Redpanda
        +--> vision-service
        +--> device-service
                  |
                  v
                EMQX MQTT
                  |
                  v
        Android 工控机 / 柜端 App
                  |
        门锁 / 摄像头 / 可选重力传感器
```

### 2.2 模块职责

| 模块 | 当前路径 | 职责 |
|------|----------|------|
| 用户小程序 | `clients/miniapp` | 登录、扫码开门、订单、充值、争议、补货员任务入口 |
| 运营后台 | `clients/admin` | 设备、订单、用户、SKU、补货、仓库、风控、对账、SLA、RBAC |
| trade-service | `services/trade-service` | 购物会话、订单、支付、库存、仓库、补货、运营、财务、权限 |
| device-service | `services/device-service` | 设备指令、MQTT 连接、设备事件转发 |
| vision-service | `vision-service` | YOLO/第三方商品理解、SKU 映射、多摄融合、异步识别 |
| Android 柜端 | `edge/android-app` | 摄像头、门锁、上传、心跳、OTA、离线续传 |
| 设备模拟器 | `edge/device-simulator` | 本地联调开门、关门、上传、重力和多摄事件 |
| 协议定义 | `proto/cabinet.proto` | 云端与设备事件、指令和识别结果结构 |
| 基础设施 | `infra` | PostgreSQL、Redis、EMQX、MinIO、Redpanda、Gateway |

### 2.3 环境分层

| 环境 | 目标 | 关键配置 |
|------|------|----------|
| dev | 本地开发联调 | `SPRING_PROFILES_ACTIVE=dev`、mock 登录/支付、MinIO、YOLO 或 mock |
| staging | 预发验证 | 关闭核心 mock，保留可控 SMS webhook 和测试支付配置 |
| prod | 商业生产 | 强密钥、真实 SMS、微信/支付宝、OSS、MQTT TLS、内部 API 隔离 |

生产必须显式设置 `SPRING_PROFILES_ACTIVE=prod`，不能依赖默认 dev profile。

---

## 3. 端到端业务流程

### 3.1 消费者购物流程

```text
用户登录
  -> 绑定手机号 / openId
  -> 扫码选择设备
  -> 创建购物会话
  -> 用户实名、余额、免密、黑名单、频率风控校验
  -> trade-service 请求 device-service 下发开门
  -> device-service 通过 MQTT 发送 OpenDoorCommand
  -> 柜端开锁并 ACK
  -> 用户取货并关门
  -> 柜端上传图片/视频到 MinIO 或 OSS
  -> 柜端上报 DoorEvent(CLOSED, videoUri)
  -> trade-service 进入 RECOGNIZING
  -> vision-service 返回 SKU 清单和置信度
  -> trade-service 结算、扣款、扣库存、生成订单
  -> 低置信度或异常进入争议审核
```

会话状态以 `proto/cabinet.proto` 为准：

| 状态 | 含义 |
|------|------|
| `CREATED` | 会话已创建，等待开门 |
| `OPENING` | 开门指令已下发 |
| `SHOPPING` | 门已打开，用户取货中 |
| `WAITING_UPLOAD` | 关门但视频仍在本地队列 |
| `RECOGNIZING` | 视频已上传，等待识别 |
| `SETTLING` | 识别完成，正在扣款与扣库存 |
| `COMPLETED` | 正常完成 |
| `DISPUTED` | 待人工审核 |
| `FAILED` | 会话失败或取消 |

### 3.2 补货流程

```text
系统按库存、货道、动销、临期生成补货建议
  -> 调度生成补货路线和任务
  -> 仓库创建出库单并按 FEFO 拣货
  -> 出库发运，形成在途库存
  -> 补货员到柜扫码签到
  -> 补货模式开门，不触发消费者结算
  -> 下架过期/临期/破损商品
  -> 上架商品，录入 SKU、批次、效期、货道、数量
  -> 关门后上传补货快照
  -> 系统回写 device_sku_lot、device_slot、inventory_movement
  -> 任务完成并释放在途库存
```

补货开门使用 `/api/v2/ops/restock/open-door`，不得复用消费者购物会话逻辑直接扣款。

### 3.3 仓库到柜机流程

规模化平台必须按批次和效期管理：

```text
SKU 建档
  -> 采购/供应商到货
  -> 仓库入库，录入 batch_no、production_date、expiry_date、purchase_cost
  -> 仓内库存 FEFO
  -> 补货任务触发出库
  -> 出库单 PICKED
  -> 出库单 SHIPPED，生成在途
  -> 柜内上架，形成 device_sku_lot
  -> 销售按 FEFO 扣减柜内批次
  -> 过期/破损/盘点差异进入报损
```

### 3.4 争议、退款与人工审核

触发争议的典型条件：

- vision-service 返回 `need_review=true`
- 总置信度低于 SKU 或系统阈值
- 识别为空但门已开关
- 视频缺失、上传失败、无法下载
- 重力 delta 与视觉 SKU 不一致
- 扣款失败但已识别到商品
- 用户主动发起订单争议

争议单由运营后台处理，必须保留视频、识别结果、订单行、库存动作、审核人和审核时间。退款和补扣必须产生审计日志和财务流水。

### 3.5 对账与商户分账

财务闭环：

```text
充值 / 购物支付 / 退款 / 补扣
  -> 平台账本
  -> 下载微信/支付宝渠道账单
  -> payment_reconciliation 对账
  -> 差异处理
  -> order_revenue_split 计算商户分润
  -> 微信分账提交 / 查询 / 重试
  -> 财务报表
```

生产启用分账时必须配置微信支付 V3 证书、API v3 key、平台证书或自动拉取能力。

---

## 4. 服务设计

### 4.1 trade-service

`trade-service` 是业务主服务，聚合交易、库存、运营和财务。主要职责：

- 用户认证：短信登录、密码登录、微信登录、token 刷新
- 用户账户：实名/信用校验、余额、免密签约、openId 绑定
- 会话状态机：创建、开门、关门、识别、结算、争议、失败
- 订单：订单行、金额、支付状态、收入分成、用户订单查询
- 支付：充值预下单、微信/支付宝回调、mock 支付仅 dev 可用
- 库存：SKU、设备库存、批次库存、货道陈列、盘点、报损
- 仓库：入库、库存、出库、拣货、发运、在途
- 补货：建议、路线、任务、任务行、补货开门、任务完成
- 运营：设备、用户、订单、报表、视频下载、审计日志
- 风控：黑名单、频率限制、风险事件
- SLA：实时指标、日快照、争议超时告警
- RBAC：角色、权限、用户角色、商户数据范围

所有用户与运营 API 使用 `/api/v2/**`；服务间 API 使用 `/internal/v1/**`。

### 4.2 device-service

`device-service` 负责云端到设备的通信适配：

- 从 trade-service 接收开门、强制关门、OTA 等内部指令
- 通过 EMQX/MQTT 向柜端下发 `DeviceCommand`
- 订阅柜端 `DeviceEvent`
- 将门状态、ACK、心跳、视频分片元数据转发给 trade-service
- 维护设备在线状态和最后心跳时间

device-service 不应持有交易结算规则；它只负责可靠通信和协议转换。

### 4.3 vision-service

`vision-service` 是识别服务，生产建议采用第三方商品理解优先、YOLO 兜底的混合模式：

| 配置 | 用途 |
|------|------|
| `RECOGNIZER_BACKEND=yolo` | 本地开发和演示 |
| `RECOGNIZER_BACKEND=aliyun` | 生产第三方商品理解 |
| `RECOGNIZER_BACKEND=hybrid` | 生产推荐，第三方优先，失败兜底 |
| `MOCK_ENABLED=false` | 生产必须关闭 mock |

主要 API：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/health` | 探针、后端状态、模型状态 |
| POST | `/api/v2/vision/recognize` | 按 `video_uri` 或多摄 clips 识别 |
| POST | `/api/v2/vision/recognize/upload` | 上传文件识别，运营预览/测试用 |
| POST | `/api/v2/vision/recognize/async` | 创建异步识别任务 |
| GET | `/api/v2/vision/tasks/{task_id}` | 查询识别任务 |

`/api/**` 必须校验 `X-Internal-Api-Key` 或等价内部密钥。

### 4.4 柜端 Android

柜端职责：

- 设备注册、读取设备 ID、维护 MQTT 连接
- 接收开门指令，驱动门锁
- 摄像头预览与关门抓拍/录制
- 上传图片/视频到对象存储
- 断网时本地缓存并恢复后续传
- 发送门状态、ACK、心跳、版本、传感器指标
- OTA 下载、校验、安装和版本上报
- 补货模式区分，避免触发消费者订单

柜端必须以状态机实现，不能只按按钮事件拼接流程。所有上行事件都要可重试、幂等、带时间戳。

### 4.5 小程序与运营后台

小程序面向消费者和补货员：

- 消费者：登录、扫码开门、设备商品、订单、充值、争议
- 补货员：任务列表、到柜签到、补货开门、任务行、货道/批次录入

运营后台面向内部和商户：

- 设备、订单、会话、用户、SKU、视频、报表
- 争议、风控、黑名单、SLA、审计
- 仓库、补货、库存、货道、批次、过期报损
- 商户、收入分成、分账状态、财务报表
- RBAC 和商户数据权限

---

## 5. 数据模型与状态机

### 5.1 核心领域

| 领域 | 关键模型 |
|------|----------|
| 用户 | `UserInfo`、`UserAccount`、`SmsVerificationCode` |
| 设备 | `DeviceInfo`、`DeviceSlot`、`OtaRelease` |
| 商品 | `SkuCatalog`、`SkuVisionMapping`、`AliyunCategoryMapping` |
| 会话 | `ShoppingSession` |
| 订单 | `CabinetOrder`、`CabinetOrderLine` |
| 库存 | `DeviceSkuInventory`、`DeviceSkuLot`、`InventoryMovement`、`InventoryWriteOff` |
| 仓库 | `Warehouse`、`WarehouseInventory`、`WarehouseInbound`、`WarehouseOutbound`、`WarehouseInTransit` |
| 补货 | `ReplenishmentRoute`、`ReplenishmentTask`、`ReplenishmentTaskLine`、`PullOffTask` |
| 支付 | `RechargeOrder`、`PaymentReconciliation`、`PaymentPlatformBillLine` |
| 分账 | `Merchant`、`OrderRevenueSplit` |
| 风控 | `RiskEvent`、`UserBlacklist` |
| 权限 | `OpsRole`、`OpsPermission`、`OpsUserRole`、`OpsRolePermission`、`OpsUserMerchant` |
| 审计 | `AdminAuditLog` |
| SLA | `SlaDailySnapshot` |

### 5.2 会话状态机

```text
CREATED
  -> OPENING
  -> SHOPPING
  -> WAITING_UPLOAD
  -> RECOGNIZING
  -> SETTLING
  -> COMPLETED

异常分支：
CREATED / OPENING / SHOPPING -> FAILED
RECOGNIZING / SETTLING -> DISPUTED
WAITING_UPLOAD -> FAILED 或 RECOGNIZING
```

状态迁移规则：

- 创建会话前必须完成用户校验、设备校验和风控校验。
- `OPENING` 后必须等待柜端 ACK 或门状态事件，超时进入失败或运营可取消。
- `SHOPPING` 只有收到关门事件后才能进入上传或识别。
- `WAITING_UPLOAD` 不允许触发结算。
- `RECOGNIZING` 必须记录 `video_uri`、clips、fusion mode 和识别任务 ID。
- `SETTLING` 必须保证订单、扣款、库存动作幂等。
- `COMPLETED`、`DISPUTED`、`FAILED` 为终态，除争议审核外不得随意回退。

### 5.3 库存状态

库存有三层：

| 层级 | 用途 |
|------|------|
| 仓库库存 | 中央仓/前置仓批次库存，负责采购、入库、出库 |
| 在途库存 | 已发运但未上架到柜的货物 |
| 柜内库存 | 设备 SKU、批次、货道和可售数量 |

销售扣减优先按柜内批次 FEFO 扣减。若识别无法定位货道，则按 `device_id + sku_id` 下最早过期批次扣减。盘点和报损必须写 `InventoryMovement`，不能直接改数量后丢失原因。

### 5.4 支付与订单状态

购物订单与充值订单分开：

- 充值订单用于用户余额或免密支付签约链路。
- 购物订单由购物会话产生，包含 SKU 行、识别置信度、批次号和支付状态。
- 微信/支付宝回调必须验签并幂等处理。
- 退款、补扣、争议调整必须形成审计日志和财务流水。

---

## 6. API 与协议设计

### 6.1 API 分层

| 类型 | 前缀 | 访问方 |
|------|------|--------|
| 用户 API | `/api/v2/auth`、`/api/v2/account`、`/api/v2/sessions`、`/api/v2/orders`、`/api/v2/payment`、`/api/v2/disputes` | 小程序 |
| 设备展示 API | `/api/v2/devices` | 小程序、运营后台 |
| 运营 API | `/api/v2/ops/**`、`/api/v2/ops/admin/**` | 运营后台、补货员端 |
| 支付回调 | `/api/v2/payment/wechat/notify`、`/api/v2/payment/alipay/notify` | 支付渠道 |
| 内部 API | `/internal/v1/**` | 服务间调用，不暴露公网 |
| 视觉 API | `/api/v2/vision/**` | trade-service、运营预览 |

### 6.2 关键用户 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v2/auth/sms-code` | 发送验证码 |
| POST | `/api/v2/auth/login` | 手机号登录 |
| POST | `/api/v2/auth/wx-login` | 微信登录 |
| GET | `/api/v2/devices/{deviceId}/status` | 设备状态 |
| GET | `/api/v2/devices/{deviceId}/products` | 柜内可售商品 |
| POST | `/api/v2/sessions` | 创建购物会话并开门 |
| GET | `/api/v2/sessions/{sessionId}` | 查询会话 |
| GET | `/api/v2/sessions/{sessionId}/order` | 查询会话订单 |
| GET | `/api/v2/orders` | 查询订单列表 |
| POST | `/api/v2/payment/recharge/prepay` | 充值预下单 |
| POST | `/api/v2/disputes` | 用户发起争议 |

### 6.3 关键运营 API

| 能力 | 路径 |
|------|------|
| 运营概览 | `/api/v2/ops/admin/stats`、`/trend`、`/reports/devices` |
| 设备管理 | `/api/v2/ops/admin/devices`、`/devices/{deviceId}/detail` |
| 会话与订单 | `/api/v2/ops/admin/sessions`、`/orders`、`/orders/{orderId}` |
| SKU | `/api/v2/ops/admin/skus` |
| 视觉映射 | `/api/v2/ops/admin/vision-mappings` |
| 争议 | `/api/v2/ops/disputes` |
| 风控 | `/api/v2/ops/admin/risk/events`、`/risk/blacklist` |
| 库存 | `/api/v2/ops/admin/inventory`、`/devices/{deviceId}/lots` |
| 货道 | `/api/v2/ops/admin/devices/{deviceId}/slots` |
| 补货 | `/api/v2/ops/admin/replenishment/*` |
| 仓库 | `/api/v2/ops/admin/warehouse/*` |
| 财务 | `/api/v2/ops/admin/finance/*`、`/reconciliation/*` |
| 商户分账 | `/api/v2/ops/admin/merchants/*` |
| OTA | `/api/v2/ops/admin/ota/releases` |
| SLA | `/api/v2/ops/admin/sla` |
| RBAC | `/api/v2/ops/admin/rbac/*` |
| 审计 | `/api/v2/ops/admin/audit-logs` |

### 6.4 内部 API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/internal/v1/sessions/door-event` | 柜端门事件转发 |
| POST | `/internal/v1/sessions/video` | 视频上传完成通知 |
| POST | `/internal/v1/sessions/gravity-deltas` | 重力变化上报 |
| POST | `/internal/v1/devices/{deviceId}/heartbeat` | 设备心跳 |
| GET | `/internal/v1/devices/{deviceId}/ota/check` | OTA 检查 |
| GET | `/internal/v1/devices/{deviceId}/inventory-snapshot` | 柜端库存快照 |
| GET | `/internal/v1/vision/mappings` | 视觉类目到 SKU 映射 |
| GET | `/internal/v1/vision/default-sku` | 默认 SKU 兜底 |

内部 API 必须带 `X-Internal-Api-Key`，并由 Gateway 或内网策略阻断公网访问。

### 6.5 MQTT 协议

目标消息结构以 `proto/cabinet.proto` 为准。当前 `device-service` 仍兼容 JSON MQTT 载荷；生产演进时应统一到 Protobuf 或在版本字段中明确协议格式，避免柜端与云端协议漂移。

| 方向 | 消息 | 说明 |
|------|------|------|
| 云端到设备 | `DeviceCommand.open_door` | 开门，包含 `session_id`、`user_id`、过期时间、运营模式 |
| 云端到设备 | `DeviceCommand.force_close` | 强制关门或取消 |
| 云端到设备 | `DeviceCommand.ota_upgrade` | OTA 升级指令 |
| 设备到云端 | `DeviceEvent.door` | 门开、门关、上传状态、多摄 clips |
| 设备到云端 | `DeviceEvent.ack` | 指令 ACK |
| 设备到云端 | `DeviceEvent.heartbeat` | 在线心跳、版本、指标 |
| 设备到云端 | `DeviceEvent.video_chunk` | 视频分片元数据 |

推荐 topic 规范：

```text
cabinet/{deviceId}/command
cabinet/{deviceId}/event
cabinet/{deviceId}/ack
cabinet/{deviceId}/heartbeat
```

生产 MQTT 必须启用 TLS、ACL、设备级凭证或证书，禁止匿名连接。

### 6.6 错误码规范

所有业务 API 返回统一 `ApiResponse` 结构。错误码建议按域划分：

| 前缀 | 领域 |
|------|------|
| `AUTH_*` | 登录、token、权限 |
| `USER_*` | 实名、余额、免密、黑名单 |
| `DEVICE_*` | 离线、门锁、心跳、OTA |
| `SESSION_*` | 会话状态、超时、幂等 |
| `VISION_*` | 识别、视频、低置信度 |
| `PAYMENT_*` | 充值、扣款、回调、退款 |
| `INVENTORY_*` | 库存不足、批次、盘点、报损 |
| `WAREHOUSE_*` | 入库、出库、在途 |
| `OPS_*` | 运营、RBAC、审计 |

---

## 7. AI 识别与结算策略

### 7.1 识别后端策略

本项目不把自研模型作为生产上线前置条件。生产默认方案：

```text
柜端图片/视频 -> OSS URL -> vision-service
  -> 阿里云商品理解或其他第三方识别
  -> 类目 ID / 标签 / 置信度
  -> sku_vision_mapping / aliyun_category_mapping
  -> SKU 清单
  -> trade-service 结算或争议
```

本地开发使用 YOLO 或 mock，是为了联调闭环，不代表生产识别精度。

### 7.2 SKU 映射

识别服务不直接决定商品价格。识别输出必须映射到平台 SKU：

| 映射表 | 来源 | 用途 |
|--------|------|------|
| `sku_vision_mapping` | YOLO 类名 | 本地开发和演示 |
| `aliyun_category_mapping` | 第三方类目 ID | 生产商品理解 |

映射由运营后台维护，并通过 `/internal/v1/vision/mappings` 提供给 vision-service 缓存。

### 7.3 置信度与结算

推荐策略：

| 场景 | 处理 |
|------|------|
| 单 SKU 高置信度 | 自动结算 |
| 多 SKU 高置信度 | 自动结算并记录每行置信度 |
| 总置信度低 | 进入争议审核 |
| SKU 映射缺失 | 进入争议审核，并提示运营补映射 |
| 识别为空 | 若重力也无变化，可零元完成；否则争议 |
| 视频缺失 | 等待上传或失败转人工 |
| 支付失败 | 保留订单和风险事件，触发补扣或人工处理 |

### 7.4 多摄与重力融合

多摄用于降低遮挡：

- `video_clips` 记录 TOP、SIDE 等多路素材
- `camera_fusion_mode=SINGLE|MULTI`
- vision-service 可对多路输出做融合

重力用于辅助判断：

- 重力 delta 与视觉 SKU 一致，提高结算可信度
- 重力有变化但视觉为空，进入人工审核
- 视觉有 SKU 但重力无变化，按设备配置决定自动结算或审核

---

## 8. 设备端与硬件集成

### 8.1 硬件组成

规模化投放建议标准化硬件 BOM：

| 硬件 | 要求 |
|------|------|
| Android 工控机 | 可运行柜端 App、CameraX、MQTT、对象存储 SDK、OTA |
| 门锁控制板 | 支持开锁、门磁状态、异常反馈 |
| 顶部摄像头 | 覆盖柜内主要陈列面 |
| 侧向摄像头 | 可选，降低遮挡 |
| 重力传感器 | 可选，用于辅助识别和防盗 |
| 网络 | 4G/5G/Wi-Fi，以移动网络兜底 |
| 电源 | 支持断电恢复、异常上报 |

### 8.2 柜端状态机

```text
IDLE
  -> COMMAND_RECEIVED
  -> DOOR_OPENING
  -> SHOPPING
  -> DOOR_CLOSED
  -> UPLOADING
  -> REPORTED
  -> IDLE

异常：
MQTT_OFFLINE -> LOCAL_QUEUE
UPLOAD_FAILED -> LOCAL_QUEUE
LOCK_FAILED -> ERROR_REPORTED
OTA_DOWNLOADING -> OTA_VERIFYING -> OTA_READY
```

### 8.3 上传与断网续传

关门后柜端优先上传视频/图片到对象存储，并上报：

```json
{
  "sessionId": "S-xxx",
  "deviceId": "CAB-001",
  "videoUri": "oss://cabinet-videos/sessions/S-xxx.jpg",
  "uploadStatus": "UPLOADED"
}
```

断网时：

- 本地保存素材和事件队列
- 上报 `LOCAL_QUEUED` 时会话进入 `WAITING_UPLOAD`
- 恢复网络后先上传素材，再调用 `/internal/v1/sessions/video`
- 上传和上报必须幂等，重复上报不能生成重复订单

### 8.4 OTA

OTA 流程：

```text
心跳上报 appVersion
  -> /internal/v1/devices/{deviceId}/ota/check
  -> 返回版本、URL、checksum、mandatory
  -> 柜端下载
  -> SHA256 校验
  -> 安装或等待维护窗口
  -> 重启后心跳上报新版本
```

生产必须支持灰度 channel、强制升级、失败回滚和升级结果上报。

---

## 9. 运营后台与商业治理

### 9.1 RBAC 与数据权限

默认角色：

| 角色 | 权限范围 |
|------|----------|
| admin | 全部系统权限 |
| operator | 设备、订单、争议、SKU、补货 |
| replenisher | 自己的补货任务和补货开门 |
| finance | 对账、退款、分账、财务报表 |
| merchant | 商户所属设备、订单、分账和报表 |
| viewer | 只读查看 |

权限分两层：

- 功能权限：`ops:device:list`、`ops:ota:publish` 等
- 数据权限：按商户、区域、设备范围过滤

### 9.2 审计日志

必须记录审计的动作：

- 修改用户余额、实名状态、黑名单
- 取消会话、处理争议、退款、补扣
- 创建或修改 SKU、价格、视觉映射
- 调整库存、盘点、报损、补货完成
- 创建 OTA 发布
- 修改 RBAC、商户分账配置
- 提交微信分账或刷新分账状态

审计字段至少包含操作者、操作对象、请求参数摘要、变更前后关键值、IP、时间和结果。

### 9.3 SLA 与告警

实时指标：

- 设备在线率
- 开门成功率
- 关门上传成功率
- 识别耗时平均值和 P95
- 自动结算率
- 争议积压数
- 支付失败数
- 库存低于阈值设备数

告警建议：

| 告警 | 阈值示例 |
|------|----------|
| 设备离线 | 超过 5 分钟无心跳 |
| 结算超时 | 关门后 2 分钟未完成 |
| 上传失败 | 同设备连续 3 次失败 |
| 争议积压 | 超过 30 分钟未处理 |
| 对账差异 | 单日差异金额非 0 |
| 库存临期 | 距过期小于阈值 |

### 9.4 风控

风控必须在开门前执行：

- 黑名单用户禁止开门
- 频繁开门限制
- 未实名或信用不足禁止开门
- 余额不足或免密未签约禁止开门
- 设备离线或维护状态禁止开门
- 高频争议用户限制开门

风控命中需要记录 `RiskEvent`，运营后台可查询和拉黑/解黑。

---

## 10. 部署、安全与运维

### 10.1 生产必填配置

核心环境变量：

| 服务 | 变量 |
|------|------|
| trade-service | `SPRING_PROFILES_ACTIVE=prod`、`JWT_SECRET`、`INTERNAL_API_KEY`、`VISION_API_KEY`、`SPRING_DATASOURCE_*` |
| trade-service | `SMS_WEBHOOK_URL`、`WECHAT_*`、`MINIO_*`、`OBJECT_STORAGE_SCHEME`、`CORS_ORIGIN` |
| device-service | `SPRING_PROFILES_ACTIVE=prod`、`INTERNAL_API_KEY`、`AICABINET_TRADE_SERVICE_URL`、`MQTT_BROKER` |
| vision-service | `VISION_API_KEY`、`MOCK_ENABLED=false`、`RECOGNIZER_BACKEND=hybrid`、`ALIBABA_CLOUD_*`、`TRADE_SERVICE_URL` |

默认开发密钥不得进入生产。

### 10.2 网络安全

生产网络要求：

- Gateway 只暴露 `/api/v2/**` 和运营后台静态资源
- `/internal/**` 只能内网访问
- vision-service 不直接暴露公网
- MQTT 使用 TLS、ACL、设备级凭证
- PostgreSQL、Redis、MinIO/OSS 管理端仅内网
- 运营后台建议独立域名、HTTPS、IP 白名单或 VPN

### 10.3 数据安全

- 密码、JWT、API key、微信私钥放环境变量或密钥管理系统
- 不在日志输出明文 token、支付证书、手机号完整值
- 视频素材设置生命周期策略，按合规要求保留
- 数据库每日备份，保留恢复演练记录
- 敏感运营动作必须审计

### 10.4 日志与监控

日志必须包含：

- `traceId` / `requestId`
- `sessionId`
- `deviceId`
- `userId`
- `orderId`
- `recognitionTaskId`

监控维度：

- JVM、数据库连接池、HTTP 延迟、错误率
- MQTT 在线数、消息堆积、断连次数
- vision-service 识别耗时、失败率、第三方 API 错误
- Kafka/Redpanda 消费延迟
- 对象存储上传失败率

### 10.5 上线检查

上线前必须完成：

- `prod` profile 启动成功
- 所有默认密钥替换
- 微信/支付宝回调验签通过
- SMS webhook 实测可用
- OSS bucket、权限和生命周期配置完成
- MQTT TLS、ACL 和设备凭证完成
- `/internal/**` 公网不可访问
- Flyway 迁移在预发执行成功
- 运营账号、角色、商户数据权限初始化
- E2E：充值、购物、争议、补货、仓库、对账各跑通一次

---

## 11. 测试方案

### 11.1 单元测试

重点覆盖：

- 会话状态机非法迁移
- 风控开门前校验
- 识别置信度与争议判断
- 订单金额、优惠、退款、补扣
- 库存 FEFO 扣减
- 仓库入库、出库、在途释放
- RBAC 权限和商户数据范围
- 分账金额计算

### 11.2 接口测试

必须覆盖：

- 登录、刷新 token、权限失败
- 创建购物会话、查询会话、查询订单
- 柜端门事件、视频上传、重力 delta
- vision recognize、upload、async task
- 运营后台 SKU、设备、订单、争议
- 库存、货道、批次、补货任务
- 仓库入库、出库、拣货、发运、在途
- 对账、分账提交、分账刷新

### 11.3 E2E 测试

本地使用 Docker Compose、设备模拟器、小程序或 curl 脚本：

```text
用户登录
  -> 充值
  -> 创建购物会话
  -> 模拟器开门 ACK
  -> 模拟器关门并上传 testdata 图片
  -> vision-service 识别
  -> 自动结算
  -> 查询订单
  -> 校验库存扣减
```

还需要覆盖：

- 低置信度进入争议
- 断网上传 `LOCAL_QUEUED` 后恢复
- 多摄 clips 识别
- 重力 delta 与视觉融合
- 补货开门不扣款
- 仓库出库发运自动生成补货行
- 支付回调重复通知幂等

### 11.4 故障演练

生产前至少演练：

- vision-service 不可用
- OSS 上传失败
- MQTT 断连
- 柜端重复上报关门事件
- 支付回调延迟或重复
- 数据库短暂不可用
- 第三方商品理解 API 限流
- OTA 下载失败

---

## 12. 版本路线图

### Phase 1：MVP 闭环

- 小程序登录、扫码开门、订单查询
- trade-service 会话状态机和订单
- device-service MQTT 开门和门事件
- vision-service 本地 YOLO/mock
- MinIO 存储素材
- 设备模拟器 E2E

### Phase 2：商业试点

- 微信/支付宝充值和支付回调
- 生产 profile、强密钥、内部 API 鉴权
- OSS 对象存储
- 第三方商品理解 + SKU 映射
- 运营后台：设备、订单、争议、SKU、视频
- 低置信度人工审核

### Phase 3：规模化运营

- 多商户、商户数据权限、商户分账
- RBAC、审计日志
- OTA、SLA、风控、黑名单
- 断网续传、多摄融合、重力辅助
- 对账、退款、补扣和财务报表

### Phase 4：仓配库存闭环

- SKU 主数据、批次、效期、成本
- 仓库入库、出库、在途
- 补货路线、任务、任务行
- 柜内批次、货道、盘点、报损
- 销售后 FEFO 扣减
- 临期/过期预警和下架任务

### Phase 5：平台化增强

- 多城市、区域、点位、路线调度
- 商户自助后台
- 数据看板、动销预测、智能补货
- 第三方 ERP/WMS/财务系统对接
- 灰度发布、自动扩缩容、链路追踪

### Phase 6：算法增强

- 自研或定制商品识别模型
- 商品包装级识别
- 多摄时序融合
- 视觉 + 重力 + 库存先验联合推理
- 识别样本闭环和标注平台

---

## 13. 实施原则

- 交易、支付、库存、分账必须幂等。
- 柜端事件可能乱序、重复、延迟，后端状态机必须防御。
- 识别服务只输出候选 SKU 和置信度，不直接操作订单和库存。
- 任何库存变更必须产生可追溯流水。
- 运营后台所有高风险动作必须做 RBAC 和审计。
- dev mock 只能存在于 dev profile，prod 启动必须拒绝默认密钥和关键缺失配置。
- 先用第三方识别跑通商业闭环，再决定是否投入自研 CV。

---

## 14. 交付验收清单

| 分类 | 验收项 |
|------|--------|
| 消费者链路 | 登录、扫码、开门、关门、识别、扣款、订单可完整跑通 |
| 设备链路 | MQTT、ACK、心跳、OTA、离线续传、多摄、重力事件可验证 |
| AI 链路 | YOLO、本地上传、第三方识别、SKU 映射、低置信度争议可验证 |
| 支付链路 | 充值、支付回调、退款、重复回调幂等可验证 |
| 库存链路 | 销售扣减、批次 FEFO、盘点、报损、货道库存可验证 |
| 仓配链路 | 入库、出库、拣货、发运、在途、补货完成可验证 |
| 运营链路 | 争议、风控、SLA、对账、分账、RBAC、审计可验证 |
| 生产部署 | prod profile、密钥、网关、内部 API 隔离、监控、备份可验证 |

本文档作为规模化开发基线。后续新增模块时，应同步更新本文件或在相关专题文档中补充，并在此处增加引用。
