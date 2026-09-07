# Pass 3D · 库存 / 货道 / 批次深读笔记

> **日期**：2026-09-07  
> **范围**：销售扣减、退款回库、争议改单差量、FEFO 批次、货道实测、补货快照  
> **源码**：`InventoryService`、`InventoryLotService`、`DeviceSlotService`、`RestockSnapshotService`、`RefundInventoryPolicy`（3B）、结算/退款调用点  
> **前置**：[PASS_3A_MONEY.md](PASS_3A_MONEY.md) · [PASS_3B_DISPUTE.md](PASS_3B_DISPUTE.md) · [PASS_3C_MQTT.md](PASS_3C_MQTT.md)

---

## 1. 三层账本模型

| 层 | 表/实体 | 作用 |
|----|---------|------|
| SKU 汇总 | `DeviceSkuInventory` | 柜×SKU 数量；无批次柜的主账 |
| 批次 FEFO | `DeviceSkuLot` + `InventoryMovement` | 有 lot 行时**可售以批次为准**；禁止只改汇总虚扣 |
| 货道 | `DeviceSlot`（book / lastPhysicalQty） | 排面、缺货、差异告警；销售后同步实测 |

**消费者可见可售量**（`availableSellableQuantity`）：

- 柜已有任意 lot → `sumSellableQuantity`（ON_SALE / NEAR_EXPIRY，受 `blockSaleDaysBeforeExpiry`）  
- 否则 → `DeviceSkuInventory.quantity`

---

## 2. 销售扣减 `InventoryService.deductForOrder`

锁：`inv:{deviceId}`（30s 租约 / 5s 等待）；与 `InventoryLotService` **同 key**（依赖 Redisson **可重入**）。

```
ensureSettlementAllowed(device)
若 items 空 且 重力有 slot 级 delta → deductFromSlotGravity（按 slot FEFO）
否则对每个 SKU：
  有 lot 账本 或 该 SKU 有可售 lot → deductFefo → batchBySku + slotQty
  否则 → applyDelta(汇总表 -qty)；库存不足 → 409
有 slotQty → applyPhysicalAfterSale(按货道)
否则 → applyPhysicalAfterSkuSale(按 SKU 分摊到绑定货道)
返回 sku → primaryBatch（写订单行）
```

### 2.1 FEFO `deductFefo`

- 按 `expiryDate ASC`；可选限定 `slotId`  
- 跳过不可售（过期封锁窗口内等）  
- 不够 → **409** `sellable lot inventory insufficient`  
- 成功后 `syncAggregateInventory` 对齐汇总表  
- 记 `InventoryMovement` type SALE  

### 2.2 与结算关系（3A）

`finalizeOrder` **先扣库存再 charge**；PENDING 也会扣。  
测试必须覆盖：待支付关单 / 免单回库 / 仅退款不回库。

---

## 3. 回库与「仅退款」

### 3.1 `restoreForOrder`（退货退款）

对每个 SKU：

1. 有 `batchBySku` → `restoreToBatch` 原批次  
2. 有 lot 账本无批次 → `ADJ-{sku}` 批次兜底回库  
3. 无 lot → 汇总表 `+qty`  
4. 有 slot → `applyPhysicalAfterRestore`；否则按 SKU 分摊回加实测  

### 3.2 `recordRefundKeptGoods`（货已离柜）

- **不再扣库**（销售时已扣）  
- 只写 `REFUND_KEPT` 审计（delta=0），防双重计损  
- 实测不回加  

由 3B `RefundInventoryPolicy` / `waiveAndRefund(restore=false)` / 部分退「不回库行」触发。

### 3.3 `adjustForOrder`（争议 CONFIRM 改单）

```
delta = newQty - oldQty
delta > 0 → 再扣 FEFO
delta < 0 → 按原 batch 回库
```

持 `inv:` 后再进 lot 锁：必须可重入。

---

## 4. 货道 `DeviceSlotService`（销售相关）

| 方法 | 行为 |
|------|------|
| `applyPhysicalAfterSale` | 仅当 `lastPhysicalQty != null` 时 `-sold` |
| `applyPhysicalAfterSkuSale` | 无 lot slot：按同 SKU 货道账面从高到低分摊 |
| `applyPhysicalAfterRestore` / `SkuRestore` | 对称加回 |
| `applyPhysicalSnapshot` | 补货/重力写入实测（锁 `device:slot:{id}`） |
| `loadBookQtyBySlot` | 账面（lot/汇总推导）供差异告警 |

锁键：

| Key | 用途 |
|-----|------|
| `inv:{deviceId}` | 库存/批次/盘点库存写 |
| `device:slot:{deviceId}` | 货道 CRUD、快照、排面、补货记录 |

差异告警：`lastPhysicalQty` vs book；缺货/低库存行给补货建议。

---

## 5. 补货快照（不结算）

`RestockSnapshotService.applySnapshot`：

1. 有 slot 重力 → `book + delta` 写实测  
2. 有视频 → vision `INVENTORY_SNAPSHOT` → 按 SKU 分摊实测  
3. 否则 SKU 重力或 `syncPhysicalFromBook`  

**不改** `DeviceSkuInventory` / lot 销售账（补货入账走 `addRestock` / `applyReplenishmentLine`）。

---

## 6. 调用矩阵（谁改库存）

| 场景 | 扣/回 | 入口 |
|------|-------|------|
| 正常结算 / CONFIRM 无旧单 | deduct | SettlementService.finalizeOrder |
| 争议改单 | adjust | confirmDisputedItems |
| WAIVE/全额退 restore=true | restore | waiveAndRefund |
| WAIVE restore=false | REFUND_KEPT | waiveAndRefund |
| 部分退 | restore 行 / kept 行 | partialRefund |
| 补货关门 | 仅实测 | RestockSnapshotService |
| 补货完成行 | addRestock / applyReplenishmentLine | InventoryLotService |
| 报损/下架/盘点 | writeOff / pullOff / stocktake | Lot + Ops |

---

## 7. 已有测试 vs 缺口

### 已有

| 测试 / 脚本 | 覆盖 |
|-------------|------|
| InventoryServiceTest | 扣减/回库基础 |
| InventoryLotConcurrencyTest | FEFO 并发 |
| DeviceSlotConcurrencyTest / DeviceSlotServiceTest | 货道 |
| InventoryOpsConcurrencyTest | 报损等 |
| RestockSnapshot*Test | 补货快照 |
| RefundInventoryPolicyTest | 回库文案策略 |
| e2e-inventory-inout-refund / e2e-*-refund* / e2e-refund-restore-compare | 脚本 |

### 建议补测（I1–I8）

| ID | 场景 | 断言 | 落地 |
|----|------|------|------|
| I1 | 有 lot 柜可售=0 仍结算 | **409**，不虚扣汇总 | ✅ `InventoryServiceTest#deduct_lotLedgerInsufficient_doesNotTouchAggregateInventory` |
| I2 | FEFO 跨两批次 | 先近效期；订单 batch=主批次；两 lot 数量对 | |
| I3 | PENDING 后 WAIVE 回库 | 库存回到结算前；无双重 REFUND_KEPT | ✅ `SettlementWaiveInventoryTest#waiveAndRefund_restoreTrue…` |
| I4 | PENDING 后仅退款不回库 | 库存保持已扣；有 REFUND_KEPT | ✅ `SettlementWaiveInventoryTest#waiveAndRefund_restoreFalse…` |
| I5 | CONFIRM 减数量 | 差量回原 batch；实测加回 | |
| I6 | CONFIRM 加数量库存不足 | 409；订单行/支付未半提交 | |
| I7 | 重力 slot 扣减 | 指定 slot FEFO；physical 该槽 -qty | |
| I8 | 无 physical 的货道销售 | applyPhysicalAfterSale 跳过；book 仍对 | |

优先：**I1、I3、I4、I6**（与 3A/3B 金钱交汇）。

---

## 8. 优化建议（测后）

1. **锁文档化**：`inv:` 可重入约定写入架构注释；禁止换成不可重入锁。  
2. **PENDING 与库存**：产品若改为「支付成功再扣库存」，需一次性改 finalize + 全部退款路径（用 I3/I4 锁现状）。  
3. **DeviceSlotService 神类**：销售物理同步可抽 `SlotPhysicalSync`，与排面 CRUD 分离。  
4. **无 lot 柜**：补货上线后应尽快建 lot，避免长期走汇总虚账。

---

## 9. Pass 进度

| Pass | 状态 |
|------|------|
| 3A 金钱 | ✅ |
| 3B 争议 | ✅ |
| 3C MQTT | ✅ |
| **3D 库存** | ✅ 本文 |
| 3E 商户资金 | ✅ [PASS_3E_MERCHANT_WALLET.md](PASS_3E_MERCHANT_WALLET.md) |
| 单测 M/D/Q/I/W | 待做 |
