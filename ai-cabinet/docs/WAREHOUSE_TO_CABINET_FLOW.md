# 仓库到开门柜全流程（行业对标 + 本系统差距）

> **实现状态（2026-07）**  
> ✅ Phase A：柜内批次/FEFO、补货行、盘点报损 API  
> ✅ Phase B：中央仓入库/出库、路线规划自动出库单  
> ✅ Phase C：SMS/DB 实链路、MQTT 重力转发、staging E2E  
> ✅ Phase D：货道陈列图、账实差异、补货关门快照、销售后货道同步  
> ✅ P2：出库发运→自动生成补货行、货道级补货建议、补货冻结、货道 FEFO（重力带 slot 时）  
> ✅ P2+：RESTOCK 超容量校验、出库建议优先货道级聚合  
> ✅ P3：视觉 `INVENTORY_SNAPSHOT` 优先 YOLO、`order_line.batch_no` API/UI、动销 ROP、COGS 报表、`purchase_cost_cents`、planogram 模板、核心单测  
> ⚠️ 待完善：微信分账 live、在途签收/扣减、ERP/采购、staging E2E 复验

本文描述**主流 AI 开门柜/无人零售**从中央仓/前置仓到柜机销售、下架、对账的完整链路，并对照本仓库 `ai-cabinet` **已有能力与缺失项**，供产品与研发统一口径。

> 参考对象：丰 e 足食、嗨便利、友宝智能柜、魔盒 CITYBOX 等公开运营逻辑；本系统当前以 **视觉识别 + 可选重力** 为主，**已实现 WMS 出库与柜内批次/货道级运营（Phase A–D）**。

---

## 1. 全流程总览

### 1.1 业务角色

| 角色 | 职责 |
|------|------|
| **采购/商品** | SKU 建档、供应商、保质期规则、售价 |
| **仓库（WMS）** | 入库、质检、批次、拣货、出库装车 |
| **调度** | 按低库存/临期/路线生成补货计划 |
| **补货员** | 领货、到店上架、扫码/拍照、下架过期品 |
| **运营** | 争议审核、调价、设备/商户管理 |
| **财务** | 对账、分账、报损核算 |
| **消费者** | 扫码开门、取货、免密/余额支付 |
| **系统** | 识别、扣款、库存扣减、SLA、告警 |

### 1.2 端到端阶段（行业通用）

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

### 1.3 与本系统现状对照（摘要）

| 阶段 | 行业标配 | 本系统现状（2026-07） |
|------|----------|------------------------|
| 商品主数据 | SKU + 条码 + 保质期 | `sku_catalog` 含 `shelf_life_days`、临期/禁售天数 ✅ |
| 仓库/WMS | 入库单、批次、FEFO 出库 | `WarehouseService` 入库/出库/批次 FEFO ✅（简易 WMS） |
| 出库/装车 | 出库单关联补货任务 | `warehouse_outbound` 绑定 `route_id`；发运后自动生成补货行 ✅ |
| 柜内库存 | 数量 + 货道/批次/效期 | `device_sku_lot` + `device_slot` planogram + FEFO 销售扣减 ✅ |
| 补货上架 | 批次效期、货道、回写 | `replenishment_task_line` + `completeTask` 写 lots ✅；小程序货道 picker ✅ |
| 消费者购物 | 开门→识别→扣款/争议 | **已实现**（免密、SLA、重力/MQTT、货道 FEFO） ✅ |
| 临期/过期 | 告警、禁售、下架 | `ExpiryAlertScheduler` + `pull_off_task` + admin 报损 UI ✅ |
| 对账分账 | 渠道账、商户分润 | 骨架有；**微信 live 分账待配** ⚠️ |

更完整的差距分层见 **[§14 差距清单 v4](#14-差距清单-v42026-07-09-刷新)**。

---

## 2. 商品主数据（MDM）

### 2.1 行业做法

每个可售 SKU 至少包含：

| 字段 | 说明 | 示例 |
|------|------|------|
| `sku_id` / 条码 | 唯一标识 | `6901234567890` |
| 名称/规格 | 对外展示 | 东鹏特饮 500ml |
| 标价 | 柜内零售价（可随点位变价） | 500 分 |
| **保质期天数** `shelf_life_days` | 自生产日期起 | 365 |
| **临期阈值** | 距过期 N 天进入临期 | 7 |
| **禁售阈值** | 距过期 N 天禁止上架/销售 | 0（过期当日 0 点） |
| 储运条件 | 常温/冷藏 | 常温 |
| 识别配置 | 视觉类名/阿里云类目映射 | `bottle` → 需改为品牌 SKU |
| 是否允许负库存销售 | 识别扣减时是否允许超卖 | 一般 false |

**变价**：同一 SKU 在不同 `merchant_id`/设备可不同价（本系统已有 `merchant` + 设备归属，SKU 价仍全局）。

### 2.2 本系统

- 表：`sku_catalog`（`price_cents`, `weight_grams`, `barcode`, `min_charge_confidence` 等）
- 缺口：无 `shelf_life_days`、`near_expiry_days`、无 `storage_type`

### 2.3 建议扩展（文档级，未建表）

```sql
-- 建议未来 migration
ALTER TABLE sku_catalog ADD COLUMN shelf_life_days INT;
ALTER TABLE sku_catalog ADD COLUMN near_expiry_days INT NOT NULL DEFAULT 7;
ALTER TABLE sku_catalog ADD COLUMN block_sale_days_before_expiry INT NOT NULL DEFAULT 0;
ALTER TABLE sku_catalog ADD COLUMN storage_type VARCHAR(16) DEFAULT 'AMBIENT';
```

---

## 3. 仓库侧（WMS）

### 3.1 入库（Inbound）

**行业流程：**

1. 采购单/到货通知（ASN）下发到 WMS  
2. 收货扫码 → 录入**生产日期** `production_date`（或仅录入**过期日期** `expiry_date`）  
3. 质检：破损、标签、冷链温度（若适用）  
4. 生成 **批次号** `batch_no`（或 `lot_id`）：`{supplier_code}-{yyyyMMdd}-{seq}`  
5. 上架到库位 `warehouse_location`  
6. 库存台账：`warehouse_inventory(warehouse_id, sku_id, batch_no, qty, expiry_date)`

**保质期处理：**

- 入库时若 `expiry_date - today < block_inbound_days`（如 30 天），**拒收**或转临期区  
- 系统按 **FEFO** 分配出库批次：优先出 `expiry_date` 最早且 `qty > 0` 的批次  

**本系统：** 无仓库表、无入库 API。

### 3.2 库内管理

| 能力 | 行业 | 本系统 |
|------|------|--------|
| 批次追溯 | 从批次查去向（哪台柜、哪次补货） | 无 |
| 盘点 | 周期盘点、差异调整 | 无 |
| 报损 | 过期/破损出库，财务记损耗 | 无 |
| 调拨 | 仓 A → 仓 B / 前置仓 | 无 |

### 3.3 出库（Outbound → 补货）

**行业流程：**

1. 系统每日/实时根据 **柜内低库存 + 销售预测 + 在途** 生成 **补货建议**  
2. 调度合并为 **出库单** `warehouse_outbound`：  
   - 行项目：`sku_id`, `batch_no`, `qty`, `expiry_date`（拣货时锁定批次）  
3. 拣货员 PDA 扫码确认 → 状态 `PICKED`  
4. 装车绑定 **补货路线** `route_id`、**补货员**、**车辆**（可选）  
5. 出库扣减 `warehouse_inventory`，增加 **在途库存** `in_transit`（可选）

**本系统：**

- 有 `replenishment_route` / `replenishment_task`（按设备维度的任务）  
- **无**出库单、无批次、无在途  
- `ReplenishmentService.planAndCreateRoute` 仅按低库存设备做路线规划（最近邻），**不扣仓库**

---

## 4. 在途与到店补货

### 4.1 补货员执行（行业 SOP）

到店标准步骤（视觉柜/重力柜通用）：

| 步骤 | 动作 | 系统记录 |
|------|------|----------|
| 1 | 小程序拉取当日任务列表 | `replenishment_task` PENDING |
| 2 | 到店扫码设备/定位校验 | `task.arrived_at`, GPS（可选） |
| 3 | **补货开门**（不结算） | 会话 `COMPLETED`，`is_restock=true` |
| 4 | **下架**：取出过期/临期品，扫码或选 SKU | `pull_off_record` 扣减柜内批次 |
| 5 | **上架**：扫码每件商品（或按货道批量） | 写入 `device_sku_lot` + 增加数量 |
| 6 | 可选：货道/层数拍照 | 附件 URL |
| 7 | 关门，确认任务完成 | `task COMPLETED`，回写库存 |

**本系统：**

- 补货开门：`POST /api/v2/ops/restock/open-door` `{ deviceId, taskId }` → 绑定任务、不扣款 ✅  
- 任务完成：`POST .../replenishment/tasks/{id}/complete` ✅（应用补货行 → 批次库存）  
- 库存：`PUT /api/v2/ops/admin/inventory` 手工改数量 ✅  
- **缺**：上架扫码、临期促销、任务与出库自动联动（部分已实现）

### 4.2 柜内库存模型（行业两种）

**A. SKU 级（适合重力柜）**

- 台账：`device_id + sku_id → quantity`  
- 销售：重力 delta 或识别结果 **扣 quantity**  
- 补货：整 SKU 加减；**批次**用单独表挂数量  

**B. 货道/批次级（适合弹簧柜/层板视觉柜）**

- 台账：`device_id + slot_id + batch_no → qty, expiry_date`  
- 销售：识别 SKU 后按 **FEFO** 扣减对应批次  
- 临期告警：按 slot/batch 扫描  

**本系统：** 仅 A 的简化版（`device_sku_inventory`），结算已 `InventoryService.deductForOrder`；**无批次**。

---

## 5. 保质期（核心细节）

### 5.1 关键概念

| 术语 | 含义 |
|------|------|
| **生产日期** | 包装喷码，入库/上架录入 |
| **过期日期** | `production_date + shelf_life_days` 或直扫包装 |
| **剩余保质期** | `expiry_date - today` |
| **FEFO** | First Expiry First Out，先出快过期批次 |
| **临期** | 剩余天数 ≤ `near_expiry_days`（常见 7 天） |
| **禁售** | 剩余天数 ≤ `block_sale_days_before_expiry`（常见 0） |

### 5.2 行业规则（推荐默认）

```text
剩余天数 > near_expiry_days     → 正常销售（GREEN）
near_expiry_days ≥ 剩余 > 禁售阈值 → 临期（YELLOW）：优先陈列前排、可自动打折
剩余 ≤ 禁售阈值                  → 禁售（RED）：不得上架、不得扣款销售
已过期                          → 必须下架报损（BLACK）
```

**注意：** 开门柜**无法在开门时逐件扫过期**（用户自取），因此行业组合策略为：

1. **上架时**录入效期（补货员责任）  
2. **周期性巡检任务**（每周/每日）补货员开门只做过期下架  
3. **系统临期报表**推送到调度  
4. **可选**：识别 + 重力一致时仍可能卖出过期品 → 靠 **巡检 + 抽检 + 客诉** 兜底；高端方案加 **RFID 效期标签**（成本高，一般试点不用）

### 5.3 各环节保质期动作

#### 仓库入库

- 录入 `expiry_date`；若 `< today + min_inbound_remaining_days`（如 30 天）→ **拒收**  
- 批次状态：`AVAILABLE` / `QUARANTINE` / `EXPIRED`

#### 出库拣货

- 按 FEFO 分配批次；同一 SKU 多批次可混装一车  
- 出库单打印：**「先出批次」** 提示补货员

#### 柜内上架

- 补货员扫条码 → 系统带出 SKU 默认保质期天数 → **可改生产日期**  
- 校验：`expiry_date > today + block_sale_days_before_expiry`，否则 **禁止上架**  
- 同一货道：**新批次放后排、旧批次放前排**（SOP，系统可提示）

#### 销售扣减（识别/重力）

- 扣减逻辑：  
  1. 查该 `device_id + sku_id` 下所有 `qty > 0` 的批次，按 `expiry_date ASC` 扣  
  2. 若最早批次已 **禁售/过期** → **不应扣减该批次**；若仅有过期批次 → 转 **争议/零扣款** 并生成 **下架工单**  
- **本系统当前**：只扣 `device_sku_inventory.quantity`，**不校验效期**

#### 临期告警

- 每日 Job：  
  - `device_sku_lot` where `expiry_date - today <= near_expiry_days` → 推送补货/运营  
  - 汇总 `near_expiry_sku_count` 进 Dashboard（类似现有 `lowStockSkuCount`）  
- 可选：设备屏/小程序 **不展示** 临期品（开门柜无屏则仅运营侧）

#### 过期下架与报损

| 步骤 | 说明 |
|------|------|
| 生成下架任务 | `pull_off_task(device_id, sku_id, batch_no, reason=EXPIRED)` |
| 补货员执行 | 补货开门 → 取出 → 扫码确认 |
| 库存 | 扣 `device_sku_lot.qty`；若 qty=0 删批次 |
| 财务 | `write_off` 记录采购成本或零售价损失 |
| 仓库 | 若带回仓报废，走 WMS 报损出库 |

### 5.4 特殊品类

| 品类 | 行业要点 |
|------|----------|
| **短保（鲜食/烘焙）** | 保质期 1～3 天；日补货；过期当日报损；售价日盘 |
| **冷藏** | 设备带温控；开门时长监控；断电告警 |
| **预包装饮料** | 保质期长；FEFO + 临期促销 |
| **组合/袋装** | 一件识别对应多个 SKU 或 bundle（需映射表） |

### 5.5 建议数据模型（批次级）

```text
device_sku_lot
  id, device_id, sku_id, batch_no
  production_date, expiry_date
  quantity, slot_id (nullable)
  status: ON_SALE | NEAR_EXPIRY | BLOCKED | PULLED
  created_at, updated_at

inventory_movement  -- 审计流水
  id, device_id, sku_id, batch_no (nullable)
  movement_type: INBOUND_REPLENISH | SALE | ADJUST | PULL_OFF | WRITE_OFF
  delta_qty, ref_type, ref_id  -- 关联 order_id / task_id / ticket_id
  operator_id, created_at
```

销售扣减伪代码（目标态）：

```text
onOrderSettled(deviceId, skuId, qty):
  lots = findLots(deviceId, skuId, status=ON_SALE, expiry_date > today)
         .orderBy(expiry_date ASC)
  remaining = qty
  for lot in lots:
    if lot.expiry_date <= today + block_sale_days: continue
    take = min(lot.quantity, remaining)
    lot.quantity -= take
    recordMovement(SALE, -take, orderId)
    remaining -= take
  if remaining > 0:
    escalateDispute("库存/批次不足或全部临期禁售")
```

---

## 6. 消费者购物（柜机 → 结算）

行业与本系统已对齐部分：

| 步骤 | 行业 | 本系统 |
|------|------|--------|
| 开门前 | 实名/免密/信用分/黑名单 | `UserValidationService` + 免密 ✅ |
| 开门 | MQTT 开锁 | device-service ✅ |
| 购物 | 录像/重力 | 视频 + 可选 `gravity_deltas` ✅ |
| 关门 | 上传 → 识别 | vision + hybrid 规划 ✅ |
| 高置信 | 自动扣款 | `SettlementService` + 置信度二次校验 ✅ |
| 低置信/空 | 争议不扣款 | `DISPUTED` + 工单 ✅ |
| 扣款 | 微信分/支付宝/余额 | `OrderPaymentService` ✅ |
| 库存 | 同步扣减 | `InventoryService` ✅（SKU 级） |
| 申诉 | 48h SLA | `DisputeSlaScheduler` ✅ |
| 退款 | 原路退 | 争议 WAIVE/ADJUST ✅ |

**缺口：** 扣减未绑批次；未在结算时拦截「仅剩余过期批次」。

---

## 7. 争议、报损与财务闭环

```text
识别争议 ──→ 运营看录像 ──→ CONFIRM / ADJUST / WAIVE
                              │
                              ├─ CONFIRM：扣款/改单 + 库存按 SKU 调整
                              ├─ WAIVE：原路退款 + 库存回滚
                              └─ ADJUST：退差/补差

过期下架 ──→ pull_off ──→ write_off（报损金额）
                          │
                          └─ 可选：供应商追溯（批次 batch_no）

日对账 ──→ 微信/支付宝账单 vs cabinet_order + recharge_order
商户分账 ──→ order_revenue_split → 微信分账 API
```

本系统：`dispute_ticket`、`payment_reconciliation`、`order_revenue_split` 已有骨架。

---

## 8. 系统模块划分（目标架构）

```text
                    ┌──────────────────────────────────────┐
                    │           trade-service               │
                    │  会话/订单/支付/争议/商户/风控/SLA    │
                    └───────────────┬──────────────────────┘
                                    │
        ┌───────────────────────────┼───────────────────────────┐
        │                           │                           │
┌───────▼────────┐        ┌─────────▼─────────┐       ┌────────▼────────┐
│ inventory-svc  │        │ replenishment-svc │       │  wms-svc (新)   │
│ 柜内+批次+流水  │        │ 路线/任务/上架确认  │       │ 入库/出库/批次   │
└───────┬────────┘        └─────────┬─────────┘       └────────┬────────┘
        │                           │                           │
        └───────────────────────────┴───────────────────────────┘
                                    │
                          device-service / MQTT / 柜机 Android
                                    │
                          vision-service / 识别 + 映射
```

**落地建议：** 首期不拆微服务，在 **trade-service** 内增加 `warehouse_*`、`device_sku_lot`、`inventory_movement` 表与 API，与现有 `ReplenishmentService`、`InventoryService` 合并演进。

---

## 9. API 清单（目标态，供排期）

### 9.1 仓库（WMS）

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v2/ops/wms/inbound` | 创建入库单 |
| POST | `/api/v2/ops/wms/inbound/{id}/receive` | 收货确认（批次+效期） |
| POST | `/api/v2/ops/wms/outbound` | 创建出库单（关联补货路线） |
| POST | `/api/v2/ops/wms/outbound/{id}/pick` | 拣货确认 |
| GET | `/api/v2/ops/wms/inventory` | 仓内批次库存 |

### 9.2 补货（扩展现有）

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v2/ops/admin/replenishment/my-tasks` | 已有 ✅ |
| POST | `/api/v2/ops/admin/replenishment/tasks/{id}/arrive` | 到店签到 |
| POST | `/api/v2/ops/admin/replenishment/tasks/{id}/lines` | 上架明细（sku, batch, expiry, qty） |
| POST | `/api/v2/ops/admin/replenishment/tasks/{id}/pull-off` | 下架报损 |
| POST | `/api/v2/ops/admin/replenishment/tasks/{id}/complete` | 已有 ✅，需改为校验明细已提交 |

### 9.3 柜内批次

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v2/ops/admin/devices/{id}/lots` | 批次列表（含效期） |
| GET | `/api/v2/ops/admin/expiry/alerts` | 临期/过期告警 |
| POST | `/internal/v1/inventory/deduct` | 内部：销售 FEFO 扣减（替代纯 SKU 扣） |

### 9.4 报表

| 报表 | 维度 |
|------|------|
| 补货执行率 | 计划 vs 完成、到店时效 |
| 临期/过期率 | 设备、SKU、商户 |
| 报损金额 | 日/周/月 |
| 动销与周转 | SKU 在柜天数、售罄天数 |

---

## 10. 与本仓库代码的映射表

| 文档概念 | 现有表/服务 | 文件/入口 |
|----------|-------------|-----------|
| SKU 主数据 | `sku_catalog` | `SkuCatalog.java`, admin 商品管理 |
| 设备 / 货道 | `device_info`, `device_slot` | `DeviceSlotService`, admin planogram |
| 柜内数量（汇总） | `device_sku_inventory` | `InventoryLotService.syncAggregateInventory` |
| 柜内批次 | `device_sku_lot` | `InventoryLotService`, FEFO 扣减 |
| 库存流水 | `inventory_movement` | `InventoryLotService.recordMovement` |
| 补货路线/任务/行 | `replenishment_*` | `ReplenishmentService`, `OpsCommercialController` |
| 补货开门 | restock session + `taskId` | `OpsService`, `shopping_session.replenishment_task_id` |
| 补货关门快照 | 货道实测 | `RestockSnapshotService`, MQTT 重力 |
| 仓库 WMS | `warehouse_*` | `WarehouseService` |
| 出库→补货行 | 发运后自动生成 | `ReplenishmentService.generateLinesFromOutbound` |
| 销售扣库存 | FEFO + 货道（重力 slot） | `SettlementService`, `InventoryService` |
| 临期/下架/报损 | `pull_off_task`, `inventory_write_off` | `ExpiryAlertScheduler`, admin 报损 UI |
| 补货建议 | 货道 par/min + **动销 ROP**（7/14 日） | `SalesVelocityService`, `GET .../replenishment/suggest`, `.../suggest/slots` |
| 订单/争议/支付 | 既有模块；订单行含 `batchNo` | `SettlementService`, `DisputeService`, admin 订单/争议 UI |
| 重力兜底 | `shopping_session.gravity_deltas` | `GravitySettlementHelper`, device-service MQTT |
| 成本价/COGS | `sku_catalog.purchase_cost_cents` → 订单行 `unit_cost_cents` | `FinanceReportService`, `GET .../finance/report`, admin「财务 COGS」 |
| planogram 模板 | 新设备自动套用；`AI_CABINET_V1` / `AI_CABINET_COMPACT` | `PlanogramTemplateService`, `POST .../slots/apply-template` |
| 点位差价 | — | **待建**（价在 `sku_catalog` 全局） |

---

## 11. 分阶段实施建议

### 11.1 试点优先级（能不能上线）

| 级别 | 含义 | 状态 |
|------|------|------|
| **P0** | 批次效期、补货行回写、流水、临期 Job | ✅ Phase A 已实现 |
| **P1** | 简易 WMS、货道 planogram、task 绑定开门 | ✅ Phase B/D + taskId 绑定 |
| **P2** | COGS、动销 ROP、视觉实盘、ERP | ✅ COGS/ROP/视觉快照已做；ERP ❌ |

### 11.2 Phase A — 能运营 ✅

已实现：`device_sku_lot`、`inventory_movement`、补货行 API、`completeTask` 写 lots、FEFO 扣减、`ExpiryAlertScheduler`、dashboard 临期/低库存统计。

### 11.3 Phase B — 能仓配 ✅（简易版）

已实现：WMS 入库/出库、路线规划自动出库、发运→补货行、GPS 签到、SKU/货道级建议、盘点/报损 API + admin UI。

### 11.4 Phase C — 能财务 ⚠️

已实现：`purchase_cost_cents`、订单行 COGS、`FinanceReportService`（今日/累计 + 7 日趋势 + SKU 毛利 Top20）、admin 财务页、报损成本汇总。  
**待 live**：微信分账生产配置与重试。

### 11.5 最小增量路径（已完成 ①～⑥）

```text
① sku_catalog 保质期字段          ✅
② device_sku_lot + movement       ✅
③ POST replenishment/tasks/lines  ✅
④ completeTask → lots + movement  ✅
⑤ FEFO + 效期禁售                 ✅
⑥ 临期/过期 Scheduler + pull_off  ✅
⑦ order_line.batch_no             ✅ API + admin 订单/争议展示
⑧ 货道 planogram + 快照           ✅ Phase D + 模板自动套用
⑨ taskId 绑定 + 补货冻结          ✅
⑩ 动销 ROP 补货建议               ✅ `SalesVelocityService`
⑪ COGS 报表                       ✅ `FinanceReportService`
```

---

## 12. 补货员小程序交互草案（上架+保质期）

```text
[任务详情 - CAB-001]
计划补货：可乐×6、薯片×4
已领出库 batch：B20260701-001 (可乐 exp 2026-08-01)

[到店] → [补货开门] → 门已开

[上架]
  扫条码 → 690... → 东鹏特饮 500ml
  生产日期 [2026-06-01]  过期 [2026-12-01]  ✅ 可售
  数量 [6]  货道 [L2-03 optional]
  [确认添加]

[下架过期]
  选择 SKU / 扫条码 → 过期批次 B20260501-003 qty 2
  原因 [过期] → [确认下架] → 生成报损

[完成补货] → 关门 → 任务 COMPLETED
```

---

## 13. 常见问题（FAQ）

**Q：没有 RFID，怎么保证不卖过期品？**  
A：行业靠 **上架录入效期 + 定期巡检下架 + 临期告警**；识别只能保证 SKU 种类，不能保证效期，除非上 RFID 或人工巡检。

**Q：识别错了会把库存扣错吗？**  
A：当前扣 **识别 SKU 数量**；争议改单会 `adjustForOrder`。批次上线后应 **改批次扣减** 并留 `inventory_movement`。

**Q：重力柜和视觉柜库存一致吗？**  
A：理想状态 **销售层统一扣 `device_sku_lot`**；重力提供实时 delta，视觉提供 SKU 分类，二者冲突进争议。

**Q：运营补货和库存数字对不上？**  
A：需 **movement 流水** 对账：期初 + 上架 − 销售 − 下架 ± 调整 = 期末；禁止仅手工改 `quantity` 无流水。

**Q：补货任务和实际上了什么货怎么对齐？**  
A：`POST .../replenishment/tasks/{id}/lines` 提交行项目；`completeTask` 按 RESTOCK/PULL_OFF 写 `device_sku_lot` 与 movement。出库发运后可自动生成补货行（含批次/货道）。

**Q：同一柜机谁都可以补货开门吗？**  
A：`openDoorForRestock` 须传 `taskId`，校验任务负责人与设备匹配；任务 `IN_PROGRESS` 期间冻结消费者开门与结算。

---

## 14. 差距清单 v6（2026-07-09 刷新）

> Phase A/B/D、P0–P5（部分）代码已落地；本节仅列 **仍缺** 或 **简化实现** 项。

### 14.1 宏观：仍缺整段

| 阶段 | 判定 | 说明 |
|------|------|------|
| 采购 / 供应商 | ❌ | 无采购单、到货计划、供应商主数据 |
| 在途签收 | ⚠️ | 发运→在途表 + 建议扣减 + `GET .../warehouse/in-transit` + 补货签收清在途 ✅；无装车扫码、独立到店签收页 |
| 微信分账 live | ⚠️ | 提交 + 重试调度 + admin refresh ✅；生产商户号/证书/余额单 wxTxnId 仍待配 |
| 财务 COGS | ✅ | `purchase_cost_cents`、订单行 `unit_cost_cents`、`GET .../finance/report`、admin 财务页 |
| 动销 ROP 补货 | ✅ | 7/14 日销量、`ropPoint`、与 par/min 合并建议（`PAR`/`ROP`/`PAR+ROP`） |
| 在途扣减建议量 | ✅ | `ReplenishmentSuggestDto.inTransitQty`；发运→在途、签收→清在途 |
| 补货签到拦截 | ✅ | `checkInAt` 必填才 `openDoorForRestock`；小程序任务卡签到 + 未签到禁开门 |

**已闭环：** 简易 WMS、柜内批次+货道、补货闭环（含 taskId 冻结 + **GPS 签到拦截**）、临期/报损、消费者购物主流程、视觉补货快照（YOLO 优先）、planogram 模板、订单/争议 batchNo（admin + 小程序）、在途库存+扣减、分账重试/refresh、核心单测 + Phase B 在途 E2E。

### 14.2 段内：仍待增强

#### 补货

| 细节 | 现状 | 还缺 |
|------|------|------|
| 建议量 | 货道 par/min + **动销 ROP** + **在途扣减** + SKU 低库存兜底 ✅ | ROP 参数 Admin 可配 |
| 行项目 / 回写 | ✅ | 扫码解析条码、上架拍照 |
| 出库联动 | 发运→在途 + 自动补货行（含 batch/expiry/slot）✅ | 出库行多货道智能 slot 分配 |
| 开门 | taskId + 负责人 + 冻结 + **签到硬拦**（500m GPS）✅ | — |
| 容量 | RESTOCK 超 maxLevel 校验 ✅ | 多货道同 SKU 智能分配 |

#### 柜内库存

| 细节 | 现状 | 还缺 |
|------|------|------|
| FEFO / 效期 | ✅；重力带 `slotId` 时按货道 FEFO ✅ | 纯视觉结算路径按 slot 扣减 |
| 账实 | 货道盘点 + SKU 盘点 API ✅ | 货道盘点→自动 ADJ 账面 |
| 三方差异 | 账/重力/视觉分别存在 | 统一差异工单 |
| 超卖 | 扣到 0 + warn | 阻断或自动争议 |

#### 保质期

| 场景 | 状态 |
|------|------|
| 上架录效期、FEFO、临期 Job、pull_off | ✅ |
| 订单/争议 batchNo 展示（admin + 小程序） | ✅ |
| 入库拒收短保、临期促销、冷藏温控 | ❌ |

#### 财务 / 集成

| 类别 | 状态 |
|------|------|
| `purchase_cost_cents`、COGS 日报/SKU 排行 | ✅ |
| 微信分账 submit + 失败重试 + refresh 对账 | ⚠️（代码就绪；`PROFIT_SHARING_ENABLED=true` + 证书 + wxTxnId） |
| ERP/钉钉、地图路况 | ❌ |

### 14.3 跨段机制

| 机制 | 现状 |
|------|------|
| `inventory_movement` | ✅ 销售/补货/报损/盘点 ADJ |
| 三库存（账/物理/可售） | 账面+货道实测+补货冻结 ✅；可售公式未统一封装 |
| 补货建议引擎 | par/min + **动销 ROP** + **在途扣减** ✅ |
| 在途库存 | 发运 `shipOutbound`→`warehouse_in_transit`；`completeTask` 签收清在途；`GET .../warehouse/in-transit` ✅ |
| 补货签到 | `POST .../tasks/{id}/check-in` + `ensureRestockDoorAllowed` 校验 `checkInAt`；小程序 ops 任务卡签到/禁开门 ✅ |
| 补货关门快照 | 货道重力 → 视觉（`INVENTORY_SNAPSHOT` YOLO 优先）→ SKU 重力 → 账面 mock 兜底 ✅ |

### 14.4 代码锚点（维护用）

| 能力 | 文件 / API |
|------|------------|
| 货道 / 快照 | `DeviceSlotService`, `RestockSnapshotService` |
| 动销 ROP | `SalesVelocityService`, `RopProperties`（`aicabinet.replenishment.rop.*`） |
| 补货 / 出库 | `ReplenishmentService`, `WarehouseService` |
| **在途库存** | `InTransitService`, `V32__warehouse_in_transit.sql`；`POST .../outbounds/{id}/ship`；`GET .../warehouse/in-transit`；`completeTask` 签收 |
| planogram | `PlanogramTemplateService`, `POST .../devices/{id}/slots/apply-template` |
| **签到 / 开门** | `DeviceValidationService.ensureRestockDoorAllowed`（`REPLENISHMENT_CHECK_IN_REQUIRED`）；`POST .../tasks/{id}/check-in`；`POST .../restock/open-door` |
| 冻结 / task 绑定 | `DeviceValidationService`, `OpsService` |
| FEFO / 流水 | `InventoryLotService`, `InventoryService` |
| COGS 报表 | `FinanceReportService`, `GET .../finance/stats`, `GET .../finance/report` |
| **微信分账** | `WeChatProfitSharingService`, `ProfitSharingRetryScheduler`；`POST .../revenue-splits/{id}/wechat-submit`、`.../wechat-refresh` |
| 视觉补货快照 | `vision-service/.../yolo_recognizer.py`（`INVENTORY_SNAPSHOT`） |
| 临期 Job | `ExpiryAlertScheduler` |
| 小程序 batch | `clients/miniapp/utils/common.js`（`formatLineItem`）、`dispute-mine`、`result`、`OrderService.buildLineSummary` |
| 小程序补货签到 | `clients/miniapp/pages/ops/ops.js`（任务卡签到/禁开门）、`replenish-task` |
| 单测 | `DeviceSlotServiceTest`, `RestockSnapshotServiceTest`, `ReplenishmentServiceOutboundTest`, `SalesVelocityServiceTest`, `PlanogramTemplateServiceTest`, `InTransitServiceTest`, **`DeviceValidationServiceTest`** |
| E2E | `e2e-warehouse-phase-b.ps1`（在途+扣减+签收）、`e2e-restock-snapshot.ps1`（签到+开门）、`verify-step5.ps1 -Staging` |

### 14.5 建议下一步（P5 续 / P6）

1. **staging E2E 全量复验** — `verify-step5.ps1 -Staging`  
2. **在途运营** — 装车/到店扫码签收 UI、在途超时告警  
3. **微信分账生产** — 证书、`PROFIT_SHARING_ENABLED=true`、余额单 wxTxnId 录入流程  
4. **补货智能分配** — 多货道同 SKU slot 分配策略  
5. **Admin 可配** — ROP lead/safety 天、planogram 模板 CRUD  
6. **集成** — ERP/钉钉、地图路况（路线规划增强）

---

## 15. 相关文档

- [OPS_COMMERCIAL.md](OPS_COMMERCIAL.md) — 现有补货/SLA/风控 API  
- [COMMERCIAL_ARCHITECTURE.md](COMMERCIAL_ARCHITECTURE.md) — 识别与 OSS  
- [ROADMAP.md](ROADMAP.md) — 分步上线  
- [TEST_CASES.md](TEST_CASES.md) — 用例（含 WMS/补货/货道 E2E）

---

## 16. 文档维护

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-09 | 首版：行业全流程 + 保质期细则 + Phase A/B/C |
| v1.1 | 2026-07-09 | 差距清单 v2 |
| v1.2 | 2026-07-09 | v3：对齐 Phase A/B/D 实现；§14 仅保留未闭环项 |
| v1.3 | 2026-07-09 | v4：P3 闭环（视觉快照、batchNo、ROP、COGS、planogram、单测）；§14.5 升级为 P4 |
| v1.4 | 2026-07-09 | v5：P4 闭环（在途库存+扣减、分账重试/refresh、小程序 batchNo）；§14.5 升级为 P5 |
| v1.5 | 2026-07-09 | v6：P5 签到拦截闭环（后端硬拦 + 小程序签到 UI + E2E）；在途 API/E2E 补强 |

**当前焦点：** §14.5 staging 全量 E2E、在途运营 UI、微信分账生产配置、多货道分配。
