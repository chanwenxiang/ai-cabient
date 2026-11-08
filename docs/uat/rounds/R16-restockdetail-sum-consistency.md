# R16 · restockDetail 列求和一致性 + outboundId/warehouse_outbound 耦合命名与精度实证

> 2026-11-08 · 只读轮（A 类口径）
> 环境：UAT `aicabinet_uat`（`ai-cabinet-postgres-1`）/ trade-service `127.0.0.1:18080`
> 取证方式：真实超管（`13900000001` + 图形验证码）登录取 token，**只调用 GET**；本轮**未写库、未改产品代码、未跑 mvn、未 push、未用子代理**。
> 证据分级：**A**（真库 SQL 回读 + 真实 HTTP 响应 + 源码 grounding）。本文所有数字均可在 §七 的 SQL/请求复算。

---

## 〇、两处前提订正（先钉死，避免沿旧交接稿走）

1. **「3 张 DRAFT 收料单」实为 3 张 `warehouse_monthly_close`（分仓月结单）**，不是「收料单」。
   - 库里唯一满足「3 张 DRAFT × 0 行」的对象：`warehouse_monthly_close` 共 **3 行**（`close_id` 1/2/3），`status` 全 `DRAFT`、`year_month` 全 `2026-10`、`line_count=0`；`warehouse_monthly_close_line` **0 行**。
   - 排除其他候选（只读实查）：`warehouse_inbound` 1 张（COMPLETED、1 行）；`warehouse_outbound` 1 张（SHIPPED、1 行）；`replenishment_route` 4 条（`PLANNED/CANCELLED/PLANNED/COMPLETED`，**无 DRAFT**）；`replenishment_task` 4 条全 COMPLETED 且共 15 行。
   - 读面回读：`GET /api/v2/ops/admin/warehouse/close-sheets` → 3 行，`lineCount` 均 0（`closeId` 3/2/1，仓库 `986516138432`/`WH-DEMO-001`/`326765247551`）。
2. **接口链路不是 `ReplenishmentService`**。全仓 `restockDetail` 只在 [WarehouseMonthlyCloseService.java:258](../../../services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseMonthlyCloseService.java) 定义；`ReplenishmentService` 内 **0 个** `detail`/`restockDetail` 方法（grep 复核）。真实链路见 §一。

---

## 一、被测面与真实链路（源码 grounding）

端点（[OpsWarehouseController.java:258-267](../../../services/trade-service/src/main/java/com/aicabinet/trade/api/OpsWarehouseController.java)）：

```
GET /api/v2/ops/admin/warehouse/monthly-close/restock-detail?warehouseId=<wh>&yearMonth=<YYYY-MM>
```

链路：

```
OpsWarehouseController.warehouseMonthlyCloseRestockDetail
  → OpsWarehouseAdminService.warehouseMonthlyCloseRestockDetail   (:100-104，只做 requireWarehouseRead)
  → WarehouseMonthlyCloseService.restockDetail                     (:258-334)
      ├─ 账期窗口 = period.minusMonths(1).atDay(26) .. period.atDay(26)   (:264-265，半开 [start,end))
      ├─ 取数：warehouse_movement 中 movement_type='OUTBOUND_SHIP' 且 created_at ∈ [start,end)  (:267-274)
      ├─ 剔除 ref_type='GUEST_WATER'（客情用水在月结是独立列）            (:277-278)
      ├─ 逐行 qty = max(-delta_qty,0)，按 (deviceId|skuId) 归组             (:285-310)
      ├─ 柜机/差额回填：按 (refId, skuId, batchNo) 回查 warehouse_outbound_line (:284, :291, :337-360)
      └─ 输出 WarehouseMonthlyCloseRestockLineDto（qty / shelvedQty / remainderQty / outboundIds / lastShippedAt）(:319-331)
```

月结「上柜」列（对照侧）在 [WarehouseMonthlyCloseService.java:145-146](../../../services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseMonthlyCloseService.java) 调 `WarehouseMonthlyCloseMath.aggregate`：

- 窗口判定：`opening = at.isBefore(start)`、`inMonth = !opening && at.isBefore(end)`（[WarehouseMonthlyCloseMath.java:60-63](../../../services/trade-service/src/main/java/com/aicabinet/trade/support/WarehouseMonthlyCloseMath.java)）——**左闭右开**，与下钻的 `>= start && < end` 同窗。
- `OUTBOUND_SHIP` 非客情 → `restockQty += max(-delta,0)`；`OUTBOUND_CANCEL` 非客情 → `restockQty -= max(delta,0)`（[:90-103](../../../services/trade-service/src/main/java/com/aicabinet/trade/support/WarehouseMonthlyCloseMath.java)）。
- `restockQty` 在**全部 movement 应用完之后**统一钳 0（[:76-80](../../../services/trade-service/src/main/java/com/aicabinet/trade/support/WarehouseMonthlyCloseMath.java)）。

---

## 二、只读数据快照（本轮起点，未写库）

| 表 | 行数 | 关键事实 |
|---|---:|---|
| `warehouse_movement` | **18** | 全部 `created_at ∈ [2026-11-06T12:46Z, 2026-11-07T06:40Z]`；`max(movement_id)=18` |
| `warehouse_outbound` | 1 | `outbound_id=9900000000000001`，WH-DEMO-001，SHIPPED，`max=9900000000000001` |
| `warehouse_outbound_line` | 1 | `device_id=EG307002410770`，SKU-WATER-001，quantity=5，`remainder_qty=0` |
| `warehouse_monthly_close` | **3** | 全 DRAFT、全 `2026-10`、`line_count=0`；`max(close_id)=3` |
| `warehouse_monthly_close_line` | **0** | — |
| `replenishment_route` / `_task` / `_task_line` | 4 / 4 / 15 | 状态无 DRAFT；`max(route_id)=4`、`max(task_id)=4` |

18 条 movement 的类型分布：

| movement_type | 行数 | Σdelta | 归入月结哪一列 |
|---|---:|---:|---|
| MANUAL_INBOUND | 12 | +720 | 采购入库（本期） |
| PURCHASE_RECEIVE | 1 | +10 | 采购入库 |
| PURCHASE_RETURN | 1 | −2 | 退货 |
| STOCKTAKE | 1 | −2 | 盘亏（lossQty） |
| BIN_STOCK | 2 | +2 | 其中 +3 → 采购入库；−1（`WRITE_OFF`）→ 报损列 |
| **OUTBOUND_SHIP** | **1** | **−5** | **上柜（restockQty）** |

> 即：18 条里只有 **1 条**是「上柜」列的真源；其余 17 条进其他列。且这条 SHIP 在 WH-DEMO-001（`warehouse_id` 分布：WH-DEMO-001 12 行、326765247551 6 行、986516138432 0 行）。

---

## 三、逐单核对：明细列求和 = 单头汇总？

**判据定义**：同一 `(warehouseId, yearMonth)` 下，对每个 SKU，
`Σ restockDetail.qty`（跨柜机行）应等于 `closeSheet.lines[sku].restockQty`（月结「领货/上柜」列）。
月结 DTO 本身没有「全表 restock 合计」字段（`WarehouseMonthlyCloseDto` 只有逐行），故按**逐 SKU** 对齐；整表求和只作辅助。

### 3.1 三张 DRAFT 月结单（`year_month=2026-10`，逐张）

| close_id | warehouseId | 单头 `line_count`（DB） | 持久化行（`monthly_close_line`） | 重算 closeSheet 行数 | 重算 ΣrestockQty | 下钻行数 | 下钻 Σqty | 逐 SKU 相等 |
|---:|---|---:|---:|---:|---:|---:|---:|---|
| 1 | 326765247551 | 0 | 0 | 0 | 0 | 0 | 0 | ✅ |
| 2 | WH-DEMO-001 | 0 | 0 | 0 | 0 | 0 | 0 | ✅ |
| 3 | 986516138432 | 0 | 0 | 0 | 0 | 0 | 0 | ✅ |

- 三张单的 `loss_qty/surplus_qty` 也均为 0，与「0 行」自洽（口径：`lossQty/surplusQty` 只对 `gapQty != null && != 0` 的行求和，见 `WarehouseMonthlyCloseSheetService`）。
- 单头 `line_count` 由生成时 `sheet.setLineCount(lineDtos.size())` 写入（[WarehouseMonthlyCloseSheetService.java:500-509](../../../services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseMonthlyCloseSheetService.java)）；本轮重算行数 = 0 = 持久值，**读面与存量一致**。

### 3.2 对照：有数据的账期（`2026-11`，实时计算，未落表单）

> 2026-11 的月结单尚未生成（任务目标账期规则：`dayOfMonth >= 26 ? 本月 : 上月`，[WarehouseMonthlyCloseJob.java:53-62](../../../services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseMonthlyCloseJob.java)；今日 11-08 ⇒ 目标 `2026-10`）。因此用 `GET monthly-close` 的实时计算对照。

| warehouseId | yearMonth | 头行数 | ΣrestockQty | 下钻行数 | 下钻 Σqty | 非零 SKU 逐项（头 / 明细） | 相等 |
|---|---|---:|---:|---:|---:|---|---|
| WH-DEMO-001 | 2026-11 | 7 | **5** | 1 | **5** | SKU-WATER-001：5 / 5（其余 SKU 均 0 / 无行） | ✅ |
| 326765247551 | 2026-11 | 6 | 0 | 0 | 0 | —（6 行只有入库，无 SHIP） | ✅ |
| 986516138432 | 2026-11 | 0 | 0 | 0 | 0 | — | ✅ |
| 上述三仓 | 2026-10 | 0/0/0 | 0 | 0 | 0 | — | ✅ |

WH-DEMO-001 / 2026-11 的真实下钻行（HTTP 原样）：

```json
{"deviceId":"EG307002410770","deviceName":"演示智能柜","skuId":"SKU-WATER-001",
 "skuName":"矿泉水 550ml","barcode":"6901028300021",
 "qty":5,"shelvedQty":5,"remainderQty":0,
 "outboundIds":"9900000000000001","lastShippedAt":"2026-11-07 14:40"}
```

DB 侧独立复算：`movement_type='OUTBOUND_SHIP' AND ref_type<>'GUEST_WATER' AND created_at ∈ 2026-11 窗口` → **1 行、qty=5**，与接口两侧同值。

**结论（当前数据）**：6 个「仓×账期」组合全部满足「下钻逐 SKU 合计 = 月结列值」；唯一非零样本 5 = 5，且 `qty = shelvedQty + remainderQty`（5 = 5 + 0）。**差异 0**。

---

## 四、差异归因：3 张 DRAFT 单 0 行 vs 18 条 `warehouse_movement`

**结论：不是丢数，是账期窗口不重叠 + 目标账期尚未月结。** 逐层证据：

1. 三张 DRAFT 单的账期是 **`2026-10`**，窗口（上海时区半开）：
   `[2026-09-25T16:00Z, 2026-10-25T16:00Z)`（`period.minusMonths(1).atDay(26).atStartOfDay(SHANGHAI)` … `period.atDay(26)`）。
2. 18 条 movement 的时间范围 = `2026-11-06T12:46Z … 2026-11-07T06:40Z`，**全部 ≥ 2026-10 窗口的 end**。
   - SQL：`count(*) filter (created_at ∈ 2026-10 窗口)` = **0**；`∈ 2026-11 窗口 [2026-10-25T16:00Z, 2026-11-25T16:00Z)` = **18**。
   - 逐仓 `2026-10 窗口内行数`：326765247551=0、WH-DEMO-001=0、986516138432=0。
3. 所以三张单的 `line_count=0` 是**正确结果**：2026-10 账期没有任何流水（演示/R3/R14 造数都发生在 11-06 之后）。重新计算 `closeSheet(2026-10)` 同样返回 0 行（§3.1）。
4. 18 条流水的归属账期是 **`2026-11`**，但该账期的月结单要到 **11-26 之后**才由任务生成（目标账期规则见上）；因此现在**没有任何持久化表单能承接这 18 行**——这正是「0 行 vs 18 行」的另一半原因。
5. 即便到 11-26 生成 2026-11 单，18 行里也只有 1 条 `OUTBOUND_SHIP` 进「上柜」列（值 5）；其余 17 条进采购入库/退货/盘亏/报损列，不会凭空让上柜列变成 18。

**记录而非遮掉**：三张 DRAFT 空单是任务在 2026-10 账期的合法产物，不宜手工删除；若后续希望「空账期不出单」，那是产品口径变更，不在本轮。

---

## 五、源码边界与已知差异（只读发现，未改码）

### 5.1 🔴 `OUTBOUND_CANCEL` 会让「下钻合计 > 列值」（潜在显示差，当前活库无样本）

- 头侧：`restockQty = Σ OUTBOUND_SHIP − Σ OUTBOUND_CANCEL`（并最终钳 0），[WarehouseMonthlyCloseMath.java:90-103,76-80](../../../services/trade-service/src/main/java/com/aicabinet/trade/support/WarehouseMonthlyCloseMath.java)。
- 下钻侧：SQL **只取 `OUTBOUND_SHIP`**，不取 `OUTBOUND_CANCEL`（[WarehouseMonthlyCloseService.java:267-279](../../../services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseMonthlyCloseService.java)）。
- 取消确实会写正 delta 的 `OUTBOUND_CANCEL`（[WarehouseService.java:1089-1099](../../../services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseService.java)；发运侧写 `−quantity` 的 `OUTBOUND_SHIP` 见 [:978-985](../../../services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseService.java)）。
- 后果：**同一账期内先发运、后整单作废未签收**时，列值会减回，下钻仍按「领货」全量展示 ⇒ `Σ下钻 > 列值`；若该期净额为负，列值钳 0 而下钻仍 > 0。
- 现有证据边界：
  - R14 曾用真库造过 `SHIP −10 / CANCEL +12 / SHIP −3`，头侧实测 `restockQty=1`（[R14 §四](R14-warehouse-monthly-close-window.md)）——按现行下钻 SQL，同一批数据下钻会是 `10+3=13`，**差 12**。该批造数已在 R14 §七 精确清理，故**本轮无法在活库复现**。
  - 单测 [WarehouseMonthlyCloseRestockDetailTest.java:68-137](../../../services/trade-service/src/test/java/com/aicabinet/trade/service/WarehouseMonthlyCloseRestockDetailTest.java) 只覆盖 SHIP / 客情 / 非数字 refId，**没有 CANCEL 用例**。
- 定性：**展示口径不一致（P3 级）**，不涉及资金/库存真值；`outboundIds` 仍能回溯到被作废的单。是否把 CANCEL 作为负行并入下钻（或改文案承认「领货口径」），需产品拍板；本轮不动码。

### 5.2 下钻与 `warehouse_outbound` 的耦合方式

- `warehouse_movement.ref_id` 是 **varchar**，`warehouse_outbound.outbound_id` 是 **bigint**；两者**没有外键**（`pg_constraint` 实测：`warehouse_movement` 只有 warehouse/sku/operator 三个 FK）。
- 下钻按 `ref_id` 做 `Long.parseLong` 后 `IN (...)` 回查（[:337-360](../../../services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseMonthlyCloseService.java)）；非数字 `refId` 不参与回填、归属留空但行照出（已有单测覆盖）。
- 配对键 = `(outboundId, skuId, batchNo)`（[:381-384](../../../services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseMonthlyCloseService.java)）。
  - 前提是「一条出库行 → 一条 SHIP 流水」，当前唯一写点是 `doShipOutbound` 的逐行循环（[WarehouseService.java:978-985](../../../services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseService.java)），成立。
  - ⚠️ 边界：若**一张出库单内两行同 `skuId+batchNo` 但属于不同柜机**，这个键不唯一，`HashMap` 后写覆盖前写 ⇒ 两行流水会归到同一台柜。当前 UAT 只有 1 条出库行，**无样本**；属静态风险登记。
- 下钻 SQL **没有** `ref_type='WAREHOUSE_OUTBOUND'` 过滤，仅靠 movement_type + 时间 + 客情剔除；目前 `OUTBOUND_SHIP` 唯一写点固定写 `WAREHOUSE_OUTBOUND`，故安全。

### 5.3 文案/契约小差

- DTO javadoc 写 `outboundIds`「**逗号**分隔」（[WarehouseMonthlyCloseRestockLineDto.java:22](../../../services/common/common-core/src/main/java/com/aicabinet/common/dto/WarehouseMonthlyCloseRestockLineDto.java)），实现用中文顿号 `String.join("、", ...)`（[WarehouseMonthlyCloseService.java:329](../../../services/trade-service/src/main/java/com/aicabinet/trade/service/WarehouseMonthlyCloseService.java)）。仅展示文案，前端原样输出（[WarehouseMonthlyCloseTab.vue:274](../../../clients/admin-vue/src/components/warehouse/WarehouseMonthlyCloseTab.vue)）。

---

## 六、`outboundId` / `warehouse_outbound` 耦合、命名清理与 JSON 精度实证

### 6.1 命名清理：下钻用 `String outboundIds`，其余面仍是 `Long outboundId`

| 面 | 字段 | 类型 | 生成类型（`openapi.ts`） |
|---|---|---|---|
| 月结上柜下钻 | `outboundIds`（复数） | **String**（多单号合并、顿号连接） | `outboundIds?: string`（[:16596](../../../packages/shared-types/src/generated/openapi.ts)） |
| 出库单列表/详情 | `outboundId` | **Long** | `outboundId?: number`（[:13700](../../../packages/shared-types/src/generated/openapi.ts)） |
| 补货任务/履约/在途/商业流 | `outboundId` | Long | `number` |

证据：
- 下钻 DTO 见 [WarehouseMonthlyCloseRestockLineDto.java:25-36](../../../services/common/common-core/src/main/java/com/aicabinet/common/dto/WarehouseMonthlyCloseRestockLineDto.java)（`String outboundIds`）；出库 DTO 见 [WarehouseOutboundDto.java:7](../../../services/common/common-core/src/main/java/com/aicabinet/common/dto/WarehouseOutboundDto.java)（`Long outboundId`）。
- 前端对下钻只做文本展示，**不 parse 成数字**（[WarehouseMonthlyCloseTab.vue:274](../../../clients/admin-vue/src/components/warehouse/WarehouseMonthlyCloseTab.vue)）——因此下钻这条面精度安全。
- 该字段从引入起就是 `String outboundIds`（`git log`：`e9fa95d2` 新建、`aa5004b0` 加 `shelvedQty/remainderQty`），**不是本轮改名**；「清理」的实质是：**下钻面刻意不复用 `Long outboundId`**，而其他面仍保留 Long。

### 6.2 🔴 bigint 雪花 ID 经 JSON 回读会丢精度（活库实测）

无全局 Jackson `Long→String` 配置（`services/**` 内 `ToStringSerializer / Jackson2ObjectMapperBuilderCustomizer / write-numbers-as-strings` 均 0 命中；`spring.jackson` 无配置），故 `Long` 按 JSON **number** 输出。活库样本 `9900000000000001 > 2^53-1 (9007199254740991)`：

```
HTTP 原始字节（GET /warehouse/outbounds）: "outboundId":9900000000000001
JSON.parse 之后                          : 9900000000000000   （off by 1）
SQL WHERE outbound_id = 9900000000000001 : 1 行
SQL WHERE outbound_id = 9900000000000000 : 0 行   ← 按回读值删除/查询 = 空操作
```

- 下钻面 `outboundIds` 是字符串，**原样保留** `"9900000000000001"`（见 §3.2 响应）。
- 因此「收口按 **id 跨度**」是可执行的唯一稳妥方式：轮首记 `max(id)`，轮末 `WHERE id > 轮首max` 删除/核对；**不要**用 JSON 回读的 ID，也**不要**按金额/数量硬删（number 精度与重复值都会误伤）。
- 本轮基线（供下一轮收口对照）：`max(movement_id)=18`、`max(outbound_id)=9900000000000001`、`max(close_id)=3`、`max(route_id)=4`、`max(task_id)=4`；`count(outbound_id > 9900000000000001)=0`。
- 先例：R14 收口即按 `movement_id 19..41` 的**造数区间**删除（[R14 §七](R14-warehouse-monthly-close-window.md)），不是按金额。

### 6.3 耦合面的只读核对

- `OUTBOUND_SHIP` 流水与出库单的匹配：`ref_type='WAREHOUSE_OUTBOUND'` 且 `ref_id = outbound_id::text` → **1 条匹配、0 条失配**。
- `warehouse_outbound_line.outbound_id` 有 FK → `warehouse_outbound`；`warehouse_movement.ref_id` 无 FK ⇒ 出库单被删/被历史清理后，流水仍在，下钻会「行照出、柜机归属留空」（单测已钉该语义）。

---

## 七、只读证据清单（可复算）

### 7.1 SQL（全部只读）

```sql
-- 1) 18 行与账期窗口
select count(*) total,
       count(*) filter (where created_at >= '2026-09-25T16:00:00+00' and created_at < '2026-10-25T16:00:00+00') in_oct_window,
       count(*) filter (where created_at >= '2026-10-25T16:00:00+00' and created_at < '2026-11-25T16:00:00+00') in_nov_window
from warehouse_movement;                                    -- 18 / 0 / 18

-- 2) 类型分布
select movement_type, count(*), sum(delta_qty) from warehouse_movement group by movement_type;
-- OUTBOUND_SHIP 1 行 / -5（其余 17 行见 §二）

-- 3) 三张 DRAFT 月结单
select close_id, warehouse_id, year_month, status, line_count from warehouse_monthly_close order by close_id;
-- 1/2/3，全 DRAFT，全 2026-10，line_count 均 0
select count(*) from warehouse_monthly_close_line;          -- 0

-- 4) 账期内 SHIP 求和（下钻真源）
select warehouse_id, sku_id, count(*), sum(-delta_qty)
from warehouse_movement
where movement_type='OUTBOUND_SHIP'
  and coalesce(upper(trim(ref_type)),'') <> 'GUEST_WATER'
  and created_at >= '2026-10-25T16:00:00+00' and created_at < '2026-11-25T16:00:00+00'
group by warehouse_id, sku_id;                              -- WH-DEMO-001 | SKU-WATER-001 | 1 | 5

-- 5) 精度与跨度
select count(*) from warehouse_outbound where outbound_id = 9900000000000001;   -- 1
select count(*) from warehouse_outbound where outbound_id = 9900000000000000;   -- 0
select count(*) from warehouse_outbound where outbound_id > 9900000000000001;  -- 0
```

### 7.2 HTTP（真实 token，只读 GET，均 `code=0`）

| 请求 | 结果 |
|---|---|
| `GET /api/v2/ops/admin/warehouse/close-sheets` | 3 行，均 DRAFT/2026-10/lineCount=0 |
| `GET /api/v2/ops/admin/warehouse/monthly-close?warehouseId=<wh>&yearMonth=2026-10` ×3 仓 | 均 0 行 |
| 同上 `yearMonth=2026-11` | WH-DEMO-001 7 行（restock=5）、326765247551 6 行（restock=0）、986516138432 0 行 |
| `GET .../monthly-close/restock-detail?warehouseId=<wh>&yearMonth=2026-10` ×3 仓 | 均 `[]` |
| 同上 `yearMonth=2026-11` | WH-DEMO-001 1 行 `qty=5/shelved=5/remainder=0/outboundIds="9900000000000001"`；另两仓 `[]` |
| `GET /api/v2/ops/admin/warehouse/outbounds?page=0&size=20` | `total=1`；原始字节 `"outboundId":9900000000000001` |

### 7.3 无写库证据

- 轮后快照与交接起点（R14 §七 收口基线）一致：`warehouse_movement=18`、`warehouse_monthly_close=3`、`warehouse_monthly_close_line=0`；本会话只发 GET（登录 token 仅存于临时文件、用后已删，未留明文）。
- `admin_audit_log` 最新行仍为 `2026-11-08 09:46Z` 的 `SCHEDULED_TASK_RUN`（早于本会话所有请求；登录未写审计）。
- 未产生 `AUDIT-R16` 之类标记行；未动 `.tmp/r15-*`、未动 peer 在制文件（`docs/PAYSCORE_INTERACTION_SPEC_2026-10-26.md`、`docs/figures/payscore-interaction-figure.png`、`scripts/templates/payscore-interaction-figure.html`）。

---

## 八、不可测 / 未覆盖（如实登记）

1. **`OUTBOUND_CANCEL` 造成的下钻差**：活库当前 0 条 CANCEL，且本轮禁写 ⇒ 只能「源码 + R14 历史造数头侧结果」推断，**未在活库同窗复现**；不宣称已实测。
2. **一张出库单内同 SKU+批次跨柜机**：UAT 仅 1 条出库行，无样本 ⇒ 配对键不唯一的风险为静态登记。
3. **2026-11 的月结单**：任务要 11-26 后才生成；本轮用实时计算端点对照，**未验证持久化表单**在 2026-11 的行值（R14 已验证「生成表单 = 同一 `closeSheet` 实现」，未在 11 月重复造写态）。
4. **浏览器 UI 复验**：未开 Playwright 真点「月结 → 上柜下钻」；本轮只到 API/JSON 层（B 类另记）。
5. 未跑 mvn / 未跑 UI 构建 / 未 push（无授权）。

---

## 九、结论

- **「明细列求和 = 单头汇总」在当前活库成立**：3 张 DRAFT（2026-10）与 3 仓 2026-11 实时计算共 6 个组合全部相等；唯一非零样本 `WH-DEMO-001/2026-11` 为 **5 = 5**（`qty = shelvedQty + remainderQty`），且与 DB 侧 `OUTBOUND_SHIP` 独立求和一致。
- **「3 张 DRAFT 0 行 vs 18 条 movement」已归因**：DRAFT 单属于 **2026-10** 账期，该窗口内 movement = **0**；18 条全在 **2026-11** 窗口，而 11 月单尚未生成（11-26 后才建）⇒ 不是丢数、不是表单缺陷。18 条里只有 1 条进「上柜」列。
- **`outboundId` 耦合/命名**：下钻面刻意用 `String outboundIds`（多单合并、精度安全、前端只展示）；出库/补货等面仍为 `Long outboundId`，JSON 按 number 输出，雪花 ID `9900000000000001` 经 `JSON.parse` **丢 1 位**（→ `9900000000000000`），SQL 按该回读值查询 **0 行**。⇒ 收口必须按 **`id > 轮首 max` 跨度**，不可按回读 ID 或金额硬删；本轮基线 `max(outbound_id)=9900000000000001`、`max(movement_id)=18`。
- **本轮 0 新缺陷、0 资金副作用、0 数据写入**；登记 1 条潜在展示差（`OUTBOUND_CANCEL` 不入下钻）、1 条静态键唯一性风险、1 条文案（逗号 vs 顿号），均待拍板后再决定是否改码。
