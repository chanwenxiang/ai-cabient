# AI 开门柜 — 业务与流程全览

> 基于本仓库 `ai-cabinet` 系统整理，结合行业主流 AI 开门柜（丰 e 足食、嗨便利、友宝、魔盒 CITYBOX 等）运营逻辑。  
> 适用对象：产品、运营、研发、补货/仓库/财务团队。

---

## 目录

1. [业务本质](#一业务本质)
2. [业务角色与职责](#二业务角色与职责)
3. [系统架构](#三系统架构)
4. [核心数据实体](#四核心数据实体)
5. [端到端业务全景](#五端到端业务全景)
6. [消费者购物流程](#六消费者购物流程核心)
7. [补货运营流程](#七补货运营流程)
8. [仓库到柜机全流程（WMS）](#八仓库到柜机全流程wms)
9. [支付体系](#九支付体系)
10. [争议处理](#十争议处理)
11. [财务与对账](#十一财务与对账)
12. [设备管理](#十二设备管理)
13. [运营后台能力](#十三运营后台能力)
14. [RBAC 权限体系](#十四rbac-权限体系)
15. [关键 API 速查](#十五关键-api-速查)
16. [系统成熟度与待完善项](#十六系统成熟度与待完善项)
17. [典型业务场景串联](#十七典型业务场景串联)
18. [相关文档索引](#十八相关文档索引)

---

## 一、业务本质

AI 开门柜是一种**无人零售**形态：消费者扫码开门，自由取货，关门后系统通过**视觉识别（+ 可选重力传感器）**判断取走商品，自动完成扣款。

与传统弹簧柜/格子柜的核心差异：

| 维度 | 传统柜 | AI 开门柜 |
|------|--------|-----------|
| 交互 | 选品 → 支付 → 出货 | 先开门 → 取货 → 后识别结算 |
| 库存感知 | 出货即扣减 | 关门后识别/重力推断 |
| 支付 | 先付后取 | 免密/信用分/余额后付 |
| 异常 | 卡货 | 识别争议、低置信度人工审核 |
| 补货 | 按货道补货 | 开门上架 + 批次/效期管理 |

---

## 二、业务角色与职责

| 角色 | 职责 |
|------|------|
| **消费者** | 扫码开门、取货、免密/余额支付、发起申诉 |
| **补货员** | 领货、到店签到、开门上架/下架、录入批次效期 |
| **运营** | 设备管理、争议审核、调价、商户管理、SLA 监控 |
| **调度/仓管** | 低库存预警、路线规划、出库拣货、在途跟踪 |
| **采购/商品** | SKU 建档、供应商、保质期规则、售价、视觉映射 |
| **财务** | 日对账、商户分账、报损核算、COGS 报表 |
| **系统** | 识别、扣款、库存扣减、风控、告警、OTA 升级 |

---

## 三、系统架构

```text
┌─────────────────────────────────────────────────────────────┐
│  客户端                                                       │
│  • 微信小程序（消费者 + 补货员）                              │
│  • 运营 Web 后台（Admin SPA）                                 │
│  • 柜机 Android App（MQTT + 录像 + 门锁）                   │
└───────────────────────────┬─────────────────────────────────┘
                            │ HTTPS /api/v2/*
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  trade-service（核心业务）                                    │
│  会话/订单/支付/争议/WMS/补货/RBAC/对账/财务/商户             │
└───────────────┬─────────────────────────┬───────────────────┘
                │ REST 内部调用            │ 识别 HTTP/Kafka
                ▼                         ▼
┌───────────────────────────┐   ┌─────────────────────────────┐
│  device-service           │   │  vision-service (Python)     │
│  MQTT 开门指令、状态跟踪   │   │  YOLO(开发) / 阿里云(生产)   │
└───────────────┬───────────┘   └─────────────────────────────┘
                │ MQTT 5.0
                ▼
         EMQX → 柜机 Android / 设备模拟器
```

**基础设施**：PostgreSQL（业务数据）、Redis（缓存/限流）、EMQX（MQTT）、MinIO/OSS（视频存储）、Kafka（可选异步识别）。

**服务端口**：

| 服务 | 端口 |
|------|------|
| trade-service | 8080 |
| device-service | 8081 |
| vision-service | 8082 |
| EMQX MQTT | 11883 |
| API Gateway | 80 |
| PostgreSQL | 15433 |
| 运营后台 | http://localhost/admin/index.html |

---

## 四、核心数据实体

### 4.1 用户与商户

| 实体 | 说明 |
|------|------|
| `UserInfo` | 用户身份、实名状态 |
| `UserAccount` | 余额、微信支付分/支付宝代扣协议 |
| `Merchant` | 多租户商户，设备归属、分账比例 |

### 4.2 设备与商品

| 实体 | 说明 |
|------|------|
| `DeviceInfo` | 柜机注册、在线状态、地理位置、所属商户 |
| `DeviceSlot` | 货道陈列图（planogram）：每货道 SKU、容量上下限 |
| `SkuCatalog` | 商品主数据：价格、重量、条码、识别置信度阈值、保质期 |
| `SkuVisionMapping` | YOLO 类名 → SKU 映射（开发环境） |
| `AliyunCategoryMapping` | 阿里云类目 → SKU 映射（生产环境） |

### 4.3 柜内库存（两层）

| 实体 | 说明 |
|------|------|
| `DeviceSkuInventory` | SKU 级汇总库存（适合重力柜） |
| `DeviceSkuLot` | 批次级库存：批次号、生产日期、过期日、货道 |
| `InventoryMovement` | 库存流水：销售、补货、报损、调整 |

### 4.4 购物流程

| 实体 | 说明 |
|------|------|
| `ShoppingSession` | 购物会话：状态机、视频 URI、重力数据、补货绑定 |
| `CabinetOrder` / `CabinetOrderLine` | 结算订单及明细（含批次号） |

### 4.5 仓库与补货

| 实体 | 说明 |
|------|------|
| `Warehouse` / `WarehouseInventory` | 中央仓及批次库存 |
| `WarehouseInbound/Outbound` | 入库单/出库单 |
| `WarehouseInTransit` | 在途库存（发运后 → 补货完成前） |
| `ReplenishmentRoute/Task/TaskLine` | 补货路线、任务、上架/下架明细 |
| `PullOffTask` / `InventoryWriteOff` | 临期下架任务、报损记录 |

### 4.6 争议与财务

| 实体 | 说明 |
|------|------|
| `DisputeTicket` | 争议工单 |
| `PaymentReconciliation` | 支付渠道日对账 |
| `OrderRevenueSplit` | 商户分账记录 |
| `RechargeOrder` | 余额充值订单 |

---

## 五、端到端业务全景

```text
┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐
│ 商品主数据 │ → │ 采购入库  │ → │ 仓内批次  │ → │ 拣货出库  │ → │ 在途/到店 │ → │ 柜内上架  │
└─────────┘   └─────────┘   └─────────┘   └─────────┘   └─────────┘   └─────────┘
                                                                    │
┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐              │
│ 财务对账  │ ← │ 争议/退款 │ ← │ 识别结算  │ ← │ 消费者购物 │ ←────────┘
└─────────┘   └─────────┘   └─────────┘   └─────────┘
       ↑              ↑
       │         ┌─────────┐
       └─────────│ 下架报损  │ ← 临期巡检 / 过期强退 / 识别差异
                 └─────────┘
```

---

## 六、消费者购物流程（核心）

### 6.1 流程概览

```text
扫柜机二维码 → 登录/授权 → 创建购物会话 → MQTT 开门
    → 用户取货（柜机录像） → 关门 → 视频上传
    → AI 识别（+ 重力融合） → 自动结算 → 扣款 → 完成
    （低置信度/空识别 → 争议工单，暂不扣款）
```

### 6.2 会话状态机

```text
CREATED → OPENING → SHOPPING → [WAITING_UPLOAD] → RECOGNIZING
    → SETTLING → COMPLETED
              ↘ DISPUTED / FAILED / CANCELLED
```

| 状态 | 含义 |
|------|------|
| `CREATED` | 会话已创建 |
| `OPENING` | 正在下发开门指令 |
| `SHOPPING` | 门已开，用户购物中 |
| `WAITING_UPLOAD` | 断网，视频本地排队待上传 |
| `RECOGNIZING` | 视频已上传，AI 识别中 |
| `SETTLING` | 识别完成，正在生成订单/扣款 |
| `COMPLETED` | 购物完成 |
| `DISPUTED` | 进入争议，待人工审核 |
| `FAILED` / `CANCELLED` | 失败/取消 |

### 6.3 详细步骤

#### 步骤 1：开门前校验

系统检查（`UserValidationService`）：

- 用户未在黑名单
- 已完成实名认证
- 已签约**微信支付分**或**支付宝代扣**，或余额 ≥ 5 元
- 未触发风控（1 小时内频繁开门 ≥ 5 次）
- 设备在线、无进行中的补货任务

#### 步骤 2：创建会话 & 开门

- 小程序调用 `POST /api/v2/sessions`，传入 `deviceId`
- 后端创建 `ShoppingSession`，状态 → `OPENING`
- `device-service` 通过 MQTT 下发 `OPEN_DOOR` 指令
- 柜机 Android App 驱动门锁（串口/Chzh 协议），开始 CameraX 录像

#### 步骤 3：购物中

- 设备上报 `door-event: OPENED` → 状态 → `SHOPPING`
- 用户自由取放商品
- 可选：重力传感器实时上报 delta（辅助识别）

#### 步骤 4：关门 & 视频上传

- 用户关门 → 设备上报 `door-event: CLOSED` + `videoUri`
- 视频上传至 MinIO（开发）/ 阿里云 OSS（生产）
- **断网场景**：上报 `uploadStatus=LOCAL_QUEUED` → 状态 `WAITING_UPLOAD`；网络恢复后补传，再触发识别

#### 步骤 5：AI 识别

- `SettlementService` 调用 `vision-service`
- 识别后端：
  - **开发**：YOLO 本地模型
  - **生产**：阿里云商品理解（`ClassifyCommodity`）→ 类目映射 SKU；`hybrid` 模式失败回退 YOLO
- 可选多摄像头融合（顶摄 + 侧摄）减少遮挡
- 识别结果与重力 delta 融合（`GravitySettlementHelper`）

#### 步骤 6：结算决策

| 识别结果 | 系统动作 |
|----------|----------|
| 高置信度 + 有商品 | 生成订单 → 扣库存（FEFO）→ 扣款 → `COMPLETED` |
| 低置信度 / 需人工复核 | 创建争议工单 → `DISPUTED`，**不扣款** |
| 空识别（未取货） | 直接 `COMPLETED`，零扣款 |
| 识别失败 | `FAILED` |

#### 步骤 7：支付扣款

扣款优先级（`PayScoreService`）：

1. **微信支付分**（免密代扣）
2. **支付宝代扣协议**
3. **账户余额**（兜底）

退款原路返回对应渠道。

#### 步骤 8：库存扣减

- `InventoryService.deductForOrder()` 按 **FEFO**（先过期先出）扣减 `device_sku_lot`
- 若重力数据带 `slotId`，支持货道级扣减
- 记录 `inventory_movement` 流水

---

## 七、补货运营流程

### 7.1 补货全景

```text
系统低库存预警 → 调度规划路线 → 自动创建 WMS 出库单
    → 仓管拣货/发运 → 在途库存记录
    → 补货员小程序领任务 → GPS 到店签到（500m 内）
    → 补货开门（不结算） → 上架/下架操作
    → 提交补货明细（SKU、批次、效期、货道、数量）
    → 关门 → 视觉/重力快照 → 完成任务 → 柜内库存更新
```

### 7.2 补货员 SOP

| 步骤 | 动作 | 系统记录 |
|------|------|----------|
| 1 | 小程序查看当日任务 | `replenishment_task` = PENDING |
| 2 | 到店 GPS 签到 | `checkInTask()`，500m 范围校验 |
| 3 | 补货开门 | `POST /api/v2/ops/restock/open-door`，绑定 taskId，**不扣款** |
| 4 | 下架过期/临期品 | 提交 `PULL_OFF` 行 |
| 5 | 上架新货 | 提交 `RESTOCK` 行（SKU、batch_no、expiry_date、slot_id、qty） |
| 6 | 关门确认 | 视觉/重力快照校验 |
| 7 | 完成任务 | `completeTask()` → 写 `device_sku_lot`，清除在途 |

### 7.3 关键约束

- 消费者开门与补货任务互斥：设备有 `IN_PROGRESS` 补货任务时，禁止消费者开门
- RESTOCK 行校验货道容量上限
- 补货员账号（userId ≥ 100000000）需 `replenisher` 角色
- 必须先签到才能开门

### 7.4 补货建议算法

`WarehouseService.suggestForDevice()` 综合考虑：

- 货道 PAR（目标陈列量）/ min / max
- 近 7/14 天动销（ROP 再订货点）
- 当前柜内库存
- 在途库存（已发运未上架）
- 优先货道级聚合建议

---

## 八、仓库到柜机全流程（WMS）

### 8.1 商品主数据

每个 SKU 包含：

| 字段 | 说明 |
|------|------|
| `sku_id` / 条码 | 唯一标识 |
| 名称/规格 | 对外展示 |
| `price_cents` | 零售价（分） |
| `shelf_life_days` | 保质期天数 |
| `near_expiry_days` | 临期阈值（默认 7 天） |
| `block_sale_days_before_expiry` | 禁售阈值 |
| `min_charge_confidence` | 最低扣款置信度 |
| `purchase_cost_cents` | 采购成本（COGS 核算） |
| 视觉映射 | YOLO 类名 / 阿里云类目 → SKU |

### 8.2 入库（Inbound）

```text
采购到货 → 录入生产日期/过期日 → 质检 → 生成批次号
    → 写入 warehouse_inventory（warehouse_id + sku_id + batch_no + qty + expiry_date）
```

- 入库时若剩余保质期 < 阈值（如 30 天），可拒收
- API：`POST /api/v2/ops/admin/warehouse/inbound`

### 8.3 出库（Outbound）

```text
低库存触发补货建议 → 规划路线 → 自动创建出库单（绑定 route_id）
    → 按 FEFO 分配批次 → 拣货确认（PICKED）
    → 发运（SHIPPED）→ 扣减仓内库存 → 记录在途 inventory
    → 自动生成补货任务行
```

### 8.4 在途库存

- 发运后：`InTransitService.recordFromOutbound()` 创建在途记录
- 补货完成：`completeTask()` 标记 `RECEIVED`，清除在途
- 补货建议时扣减在途量，避免重复补货

### 8.5 保质期管理（FEFO 核心）

| 剩余天数 | 状态 | 动作 |
|----------|------|------|
| > 临期阈值 | 正常（GREEN） | 正常销售 |
| 临期阈值 ≥ 剩余 > 禁售阈值 | 临期（YELLOW） | 优先陈列前排，可促销 |
| ≤ 禁售阈值 | 禁售（RED） | 禁止上架/销售 |
| 已过期 | 过期（BLACK） | 必须下架报损 |

**销售扣减**：按 `expiry_date ASC` FEFO 扣减；禁售/过期批次不参与扣减。

**临期告警**：`ExpiryAlertScheduler` 每日扫描 → 生成 `pull_off_task` → 推送运营/补货员。

---

## 九、支付体系

### 9.1 支付渠道

| 渠道 | 签约 | 扣款 | 退款 |
|------|------|------|------|
| 微信支付分 | `POST /api/v2/account/payscore/sign` | 关门识别后免密代扣 | 原路退 |
| 支付宝代扣 | `POST /api/v2/account/alipay-agreement/sign` | 协议扣款 | 原路退 |
| 账户余额 | 充值预支付 | 兜底扣款 | 退回余额 |

### 9.2 充值流程

```text
用户发起充值 → 微信/支付宝预支付 → 支付回调 → 余额入账
```

### 9.3 开发 vs 生产

- **开发**：`MOCK_ENABLED=true`，模拟交易号（`MOCK-PS-*`）
- **生产**：需配置真实商户证书，`liveChargeEnabled=true`

---

## 十、争议处理

### 10.1 争议触发

| 来源 | 场景 |
|------|------|
| 系统自动 | 识别置信度低于 SKU 阈值、空识别需复核、`needReview=true` |
| 消费者主动 | `POST /api/v2/disputes` 发起申诉 |

### 10.2 争议 SLA

- 默认 **48 小时**内必须处理（`DisputeSlaScheduler` 监控逾期）
- 运营后台可查看购物录像（MinIO 视频预览）

### 10.3 运营裁决

`POST /api/v2/ops/disputes/{ticketId}/resolve`：

| 裁决 | 动作 |
|------|------|
| **CONFIRM** | 按审核结果扣款，调整库存 |
| **ADJUST** | 退差/补差（`applyPaymentDelta`） |
| **WAIVE** | 全额退款 + 库存回滚 |

### 10.4 风控联动

- 7 天内争议 ≥ 3 次 → 自动拉黑 30 天
- 黑名单用户禁止开门

---

## 十一、财务与对账

### 11.1 日对账

```text
每日定时/手动触发 → 拉取微信/支付宝账单
    → 对比系统账本（cabinet_order + recharge_order）
    → 生成 payment_reconciliation 记录（差异明细）
```

API：`POST /api/v2/ops/admin/reconciliation/run?date=2026-07-06&channel=WECHAT`

### 11.2 COGS 报表

- `FinanceReportService` 统计营收、采购成本（`purchase_cost_cents`）、报损成本
- API：`GET /api/v2/ops/admin/finance/stats`、`/finance/report?days=7`

### 11.3 商户分账

- 每笔订单按商户配置比例生成 `order_revenue_split`
- 对接微信分账 API（代码就绪，生产证书待配置）

---

## 十二、设备管理

### 12.1 设备生命周期

| 环节 | 说明 |
|------|------|
| 注册 | `device_info` 录入设备 ID、商户、位置 |
| 心跳 | 柜机定期上报 → 在线/离线状态 |
| 开门指令 | MQTT `OPEN_DOOR` → 门锁驱动 → ack 跟踪 |
| OTA 升级 | 启动检查版本 → 下载 APK → 安装上报 |

### 12.2 柜机 Android App 职责

- 订阅 MQTT 主题，接收开门/OTA 指令
- CameraX 录像（消费者模式）/ 不录像（补货模式）
- 门锁串口驱动（Chzh 协议）
- 视频上传 MinIO/OSS
- 断网本地队列（`OfflineUploadQueue`）
- 重力数据上报（可选）

### 12.3 多摄像头 & 重力

- 顶摄 + 侧摄减少遮挡，`camera_fusion_mode: SINGLE | MULTI`
- 重力 delta 辅助识别准确性，支持货道级库存扣减

---

## 十三、运营后台能力

入口：`http://localhost:8080/admin/index.html`

| 模块 | 功能 |
|------|------|
| **数据概览** | 今日订单/营收/设备在线率/低库存预警 |
| **工作台** | 待处理争议、SLA 逾期、离线设备、上传队列、在途超时 |
| **设备管理** | 设备列表、状态、会话记录 |
| **订单/充值** | 订单查询、充值记录 |
| **商品管理** | SKU 维护、视觉映射配置 |
| **争议审核** | 工单列表、录像预览、裁决 |
| **补货管理** | 路线规划、任务分配、库存查看 |
| **仓库管理** | 入库/出库/批次库存/在途 |
| **财务** | COGS 报表、对账 |
| **风控** | 黑名单、风险事件 |
| **SLA 监控** | 开门成功率、识别耗时 P95、在线率 |
| **OTA** | 版本发布、设备升级跟踪 |
| **权限管理** | RBAC 角色/权限（admin/operator/replenisher/finance/viewer） |

---

## 十四、RBAC 权限体系

| 角色 | 典型权限 |
|------|----------|
| `admin` | 全部权限 |
| `operator` | 设备、争议、会话、商品 |
| `replenisher` | 补货任务、补货开门 |
| `finance` | 对账、财务报表、分账 |
| `viewer` | 只读查看 |

权限码示例：`ops:dispute`、`ops:replenishment:edit`、`ops:warehouse:inbound`

---

## 十五、关键 API 速查

### 消费者端

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v2/auth/login` | 登录 |
| POST | `/api/v2/sessions` | 创建购物会话（开门） |
| GET | `/api/v2/sessions/{id}` | 轮询会话状态 |
| GET | `/api/v2/orders` | 订单历史 |
| POST | `/api/v2/disputes` | 发起争议 |
| POST | `/api/v2/account/payscore/sign` | 签约支付分 |

### 补货员端

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v2/ops/admin/replenishment/my-tasks` | 我的补货任务 |
| POST | `/api/v2/ops/restock/open-door` | 补货开门 |
| POST | `/api/v2/ops/admin/replenishment/tasks/{id}/lines` | 提交补货明细 |
| POST | `/api/v2/ops/admin/replenishment/tasks/{id}/complete` | 完成任务 |

### 运营后台

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v2/ops/admin/stats` | 数据概览 |
| GET | `/api/v2/ops/admin/workbench` | 工作台 |
| POST | `/api/v2/ops/admin/replenishment/routes` | 规划补货路线 |
| POST | `/api/v2/ops/admin/warehouse/inbound` | 仓库入库 |
| POST | `/api/v2/ops/admin/reconciliation/run` | 触发对账 |
| POST | `/api/v2/ops/disputes/{id}/resolve` | 争议裁决 |

---

## 十六、系统成熟度与待完善项

### 已就绪（生产可用核心）

- 购物会话状态机 + MQTT 开门控制
- 视觉识别结算（YOLO 开发 / 阿里云生产）
- 多通道支付（支付分 / 支付宝 / 余额）
- 争议工单 + 48h SLA
- 补货路线规划 + 批次/FEFO 库存
- 简易 WMS（入库/出库/在途）
- 货道陈列图 + 补货快照
- 运营后台 + RBAC
- COGS 报表 + 日对账骨架
- 风控（黑名单、频繁开门/申诉）
- OTA + 断网续传 + 多摄融合

### 待完善

| 项 | 说明 |
|----|------|
| 微信分账 live 配置 | 代码就绪，生产商户证书待配 |
| ERP/采购深度集成 | 现有 PO 骨架，未对接外部 ERP |
| 在途签收 UI | API 已有，小程序扫码签收待完善 |
| 点位差异化定价 | SKU 价格目前全局，未按设备/商户变价 |
| 纯视觉货道级扣减 | 重力带 slot 时已有，纯视觉待增强 |

---

## 十七、典型业务场景串联

### 场景 A：正常购物

> 用户扫柜机码 → 支付分已签约 → 开门取 1 瓶可乐 → 关门 → YOLO 识别「可乐×1」置信度 0.92 → 自动扣 3 元 → FEFO 扣减最早批次库存 → 完成

### 场景 B：识别争议

> 用户取 2 件商品 → 识别仅 1 件且置信度 0.55 → 系统创建争议工单不扣款 → 运营看录像 → CONFIRM 2 件 → 补扣款 → 完成

### 场景 C：补货全流程

> 系统检测 CAB-001 可乐低库存 → 规划路线含 3 台柜 → 自动创建出库单 FEFO 拣货 → 补货员张三领任务 → 到店签到 → 开门上架 10 瓶（录入批次/效期/货道）→ 关门快照 → 完成任务 → 在途清零、柜内库存 +10

### 场景 D：临期下架

> 每日 Job 发现 CAB-002 某批次剩余 3 天 → 生成 pull_off_task → 补货员下次到店时 PULL_OFF 3 瓶 → 报损记录 → COGS 计入损耗

---

## 十八、相关文档索引

| 文档 | 路径 | 内容 |
|------|------|------|
| 仓库到柜机全流程 | [WAREHOUSE_TO_CABINET_FLOW.md](./WAREHOUSE_TO_CABINET_FLOW.md) | 最详细的 WMS/补货/保质期文档 |
| 商业运营模块 | [OPS_COMMERCIAL.md](./OPS_COMMERCIAL.md) | OTA/风控/对账/SLA/RBAC |
| 商业落地架构 | [COMMERCIAL_ARCHITECTURE.md](./COMMERCIAL_ARCHITECTURE.md) | OSS + 阿里云识别 |
| 系统架构 | [ARCHITECTURE.md](./ARCHITECTURE.md) | 服务边界 |
| 本地联调 | [LOCAL_SETUP.md](./LOCAL_SETUP.md) | 开发环境搭建 |
| 生产部署 | [PRODUCTION.md](./PRODUCTION.md) | 上线清单 |
