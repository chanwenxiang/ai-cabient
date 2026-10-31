# R4 审计：仓储（8 页 + `/print` 隐藏页）

- 执行 AI / 日期：Qoder（本会话）/ 2026-10-31
- 代码基线：HEAD `db20245e`（工作树干净；远端同步）
- 环境：本地 docker（trade `:18080`、nginx `:80` 提供 `/admin`）；库 `aicabinet` @15433
- 账号：超管 `13900000001`（受限角色快测留 R8）
- 工具：Playwright 驱动本机真 Chrome（`channel:'chrome'`，1600×1000，headed）；内置浏览器 702px 不判样式

## Step 0 清单重拉（`node docs/module-audit/step0-inventory.mjs` @ `db20245e`）

```
admin 侧栏登记页合计：77（仓储 8；其余与 PLAN 一致，无漂移）
无 perm 码（登录即可见）：/profile
菜单有、路由无（死入口）：无
兼容重定向（非页面）：/index.html /oper-logs /rbac
路由有、菜单未登记（人工判）：/print（已知隐藏页，R4 覆盖）
consumer-mp：main=7 sub=16 total=23
merchant-mp：main=5 sub=30 total=35
```

**分批（每批 ≤6 页）**

| 批次 | 页面 | 状态 |
|------|------|------|
| 批 1 | `/warehouse/master` 仓库与库位 · `/warehouse/procurement` 采购与供应商 · `/warehouse/claims` 待索赔台账 · `/warehouse/expiry-alerts` 近效期预警 | 进行中 |
| 批 2 | `/warehouse/monthly-close-sheets` 月结单台账 · `/warehouse/stock` 库存与盘点 · `/warehouse/reconciliation` 供应商对账单 · `/warehouse/fulfillment` 领货与调拨 · `/print` 打印单据 | 未开始 |

联动面：merchant-mp `purchase` / `self-replenish` / `warehouse-stocktake` / `device-stocktake` / `tally-records` / `prep-generate`（留 R9）。

## 本轮基线（2026-10-31，R3 清理后）

设备 **3** · SKU **15**（在架 3）· 订单 **8**（零元 6）· 会话 **64** · 营收 ¥5.00 · 仓库 1 · 库位 0 · 供应商 1 · 临期任务 1 · 盘点单 1 · 月结单 1 · 出库单 14 · 入库单 0。

## 本轮口径（勿顺手对齐）

- 分仓是补货员自己的仓（本部仓只对采购）；补货＝自提自送，**无「分仓→柜」在途段**（CB-074）。
- 在架单源 `SkuOnShelf`；缺货单源 `SkuStockLevel`；下架台账行不随动清（CB-080）。
- 供应商对账单页在 D28-B15 已补登记并挂 `ops:warehouse:list`，页内「生成对账单」由 `ops:procurement:list` 收口。

## 逐页结果

### 页面：仓库与库位（`/warehouse/master`）｜选项卡：仓库概览(1) / 货位

- 视图源码：`WarehouseMasterPage.vue`（薄壳，`:only="['warehouses','bins']"`）→ `WarehouseView.vue` + `components/warehouse/WarehouseBinsTab.vue`
- 九维要点：仓库概览 1 行 = SQL `warehouse` 1 行 ✅；工具栏 新增仓库/导入模板/导入/导出/排序/列设置；行操作 编辑。
- 货位 tab：两张表（货位档案 / 货位库存）各自空态「暂无货位，请先新增货位」「暂无货位库存」= SQL `warehouse_bin` 0 / `warehouse_bin_stock` 0 ✅；**该 tab 没有分页器**。
- 🔴 假缺陷排除：遍历脚本先报「货位 tab 0 行却显示共 1 条」——实际是 Element Plus 保留隐藏 pane，我的 `.el-pagination__total` 没加 `:visible`，读到了仓库概览的分页。**教训：跨 tab 取值必须 `:visible` 作用域**（本轮已两次踩到，第三次是采购 tab 的空态）。

### 页面：采购与供应商（`/warehouse/procurement`）｜选项卡：供应商(1) / 采购单(2) / 采购建议(0) / 采购退货(0) / 应付账款(0)

- 九维要点：供应商 1 = SQL `supplier` 1 ✅；采购单 2 = SQL `purchase_order` 2 ✅；三个空 tab 的空态文案**各自不同且语义正确**（「暂无采购建议（近 14 日有动销且库存不足…）」「暂无采购退货」「暂无应付账款」）——遍历脚本曾误报三者共用采购建议文案，逐 tab 精确复测后排除；工具栏 新增供应商/导入模板/导入/导出/打印收货单；console 0 error。
- 待深测（批 1 收尾再补）：采购单的审批/收货按钮（写操作，本轮不提交）；打印收货单走 `/print`（批 2 与打印页一起验）。

### 页面：待索赔台账（`/warehouse/claims`）

- 数据源：`inventory/write-off/claims` + `claims/summary`；SQL `inventory_write_off` 全表 **0 行** ⇒ 列表 `共 0 条` + 空态「暂无待索赔记录（责任方未认定 或 索赔额为 0 的记录不列入）」✅。
- 口径文案正确（页头明写「只列责任方已认定且索赔额>0；不是自动冲减供应商应付，追偿实际发生再走支付流程」）；筛选 责任方（供应商/物流/商户/无责任方/待判定）；「按责任方汇总」卡在位；console 0 error。
- 数据缺口：无报损单 ⇒ 索赔全链（认定责任方 → 生成理赔单号 → 冲减/支付）**未演练**，留 R11。

### 页面：仓库近效期预警（`/warehouse/expiry-alerts`）

- 数据源：`warehouse/expiry-alerts?daysAhead=30`；SQL `device_sku_lot` 10 批里 **30 天内到期 0 批**（且 6 批 DEPLETED）⇒ 页面 `共 0 条` + 空态「没有 30 天内到期的批次」✅。
- 口径文案准确：页头写明「这里只看仓库里的批次；柜机批次的到期预警在『补货预警』页（数据源不同）。本页含已过期批次」——与 R1 记的「柜台账/柜机批次」分工一致；筛选 仓库 + 提前天数。
- 数据缺口：无临近到期批次 ⇒ 行内「处理/下钻设备」动作未演练（R11 造数时补）。

## 批 1 小结

- **零新缺陷**（四次观察里两次是探针误读：跨 pane 取 `el-pagination`/`el-table__empty-text`）。
- 数值全部与 SQL 真值一致：仓库 1 / 货位 0 / 库存 0 / 供应商 1 / 采购单 2 / 报损 0 / 30 天内到期批次 0。
- 未演练的写操作与真机项已单列到对应页，留批 2 或 R11。

## 发现汇总

| 级别 | 数量 | 编号 |
|------|------|------|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 0 | — |
| P3 | 0 | — |

## 需用户拍板

- （待填）

## 需用户配合（真机）

- 入库/出库/盘点若需走真机扫码或真柜签到，单列于此。

## 修复批次建议

- （待填）

## 给下一轮的交接注记

- 新门禁 `check:list-filter-server-side` 已在 CI 生效（棘轮白名单 3 条）；R4 各页若出现「页内过滤」会直接红，按门禁提示改服务端化，不要往白名单里塞。
- 真值 SQL 注意逻辑删除：`sku_catalog.is_deleted`（R3 踩过）。
