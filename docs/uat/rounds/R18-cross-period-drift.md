# R18 — 跨账期窗口「写出后被外部改动」的 drift 数据形状（真 UAT 库 / 真 API）

> **轮次**：R18（A 类真库）。**日期**：2026-11-08。**结果**：**18 PASS / 0 FAIL，exit 0，0 残留**（内部断言 + 外部全 179 表 count(*) 双核，仅温度遥测漂移）。
> **驱动**：`.tmp/r18-drift.mjs`。**证据**：`.tmp/r18-run.log`、`.tmp/r18-after.txt`。

## 1. 被测面（对着源码读死）

`GET /api/v2/ops/admin/warehouse/close-sheets/{closeId}/drift` → `WarehouseMonthlyCloseSheetService.recalcDrift`：
- 用 `closeCalculator.closeSheet(warehouse, yearMonth)` **实时重算**当期行，与**已存** `warehouse_monthly_close_line` 的 `(expectedQty, countedQty)` 逐 SKU 比对；
- 已存行与重算不符（或重算出现已存没有的 SKU）⇒ **drifted**；`editedFlag=true` 的行**豁免**（财务手工录入的权威值）；
- 返回 `{consistent, driftedSkuCount, driftedSkuIds, editedSkuIds}`。

## 2. 造 drift 数据形状（安全、真实端点）

实库仅 2026-10 三张 0 行单（窗口无流水），无法产生 drift。用**真实 generate 端点** `POST /warehouse/close-sheets/generate {warehouseId:326765247551, yearMonth:2026-11}` 生成**有 6 行的真实单**（2026-11 有流水），此时 save=recalc。

> 造「saved≠recalc」用**改已存行**而非改 movement/库存——避免 `WAREHOUSE_LEDGER` 一致性风险；真实外部流水改动会改变 recalc 侧、走**同一条检测路径**（检测器只看 saved≠recalc 这个形状）。

## 3. 执行与断言（18 PASS / 0 FAIL）

- **初始**：drift(close 5) → `consistent=true, driftedSkuCount=0`（save=recalc）。
- **制造漂移**：改已存行 expected +100 → drift → `consistent=false, driftedSkuCount=1, driftedSkuIds=["SKU-WATER-001"]`（**检出**）。
- **还原**：expected 复原 → drift → `consistent=true`（**确定性**，非一次性的假阳）。
- **editedFlag 豁免**：改 expected 且置 `edited_flag=true` → drift → `consistent=true, editedSkuIds=["SKU-WATER-001"]`（**豁免命中**，不误报财务手工行）。

## 4. 收口与 0 残留

`DELETE warehouse_monthly_close WHERE close_id=5`（行 `ON DELETE CASCADE`）。内部断言：月结单数/行数复原、audit max 复原、**一致性记录数复原（无新 FAIL，21=21）**、生成单删净。外部全表 diff 仅温度遥测漂移。

## 5. 结论

- **drift 检测正确**：saved≠recalc 逐 SKU 检出、还原即复一致（确定性）、editedFlag 行正确豁免。
- **未发现产品缺陷**；一致性记录数无新增（未污染一致性巡检）。
- 真实外部流水改动的形状与本法等价（检测器只看 saved vs recalc），若要更贴近可后续在有量账期上以真 movement 复跑。