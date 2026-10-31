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

## 逐页结果（批 2）

### 页面：库存与盘点（`/warehouse/stock`）｜9 个选项卡

- 视图源码：`WarehouseStockPage.vue` → `WarehouseView.vue`（`:only` 子集）+ 组件 `<Warehouse*Tab>`
- 数值对真值（逐 tab 用 `:visible` 作用域读，避免跨 pane）：
  - **盘点单 1** = SQL `warehouse_stocktake` 1 ✅；行操作「盘点」；
  - **批次库存 9** = SQL `warehouse_inventory` 9 ✅；
  - **库存流水 26** = SQL `warehouse_movement` 26 ✅（页内 20 条为分页，页头明写「仅显示最近 100 条流水」）；表头 流水/类型/商品/批次/变动/关联业务/关联单号/时间；
  - **月结 tab**：**实时预览 9 行**（= 库存 SKU 数）而非 `warehouse_monthly_close` 的 1 张单据——表头 商品名称/条码/上期/采购入库/调入/退货/调出/领货，页内公式写明「应有=上期+采购入库+调入−退货−调出−领货（四损耗列单独展示不进应有）」「账期=上月26日~本月25日」「与结算金额无关」，并标注「其中没进柜的部分点该列下钻看『领而未上』（CB-074）」——口径与 CB-074 一致 ✅；
  - 其余 5 个导入类 tab（渠道对账导入/导入向导/外部商品映射/批次历史/渠道流水）空态「暂无数据」。
- 写操作：新建盘点/上传解析（未提交）；console 0 error。

### 页面：月结单台账（`/warehouse/monthly-close-sheets`）

- 数值对真值：列表 1 条 = SQL `warehouse_monthly_close` 1（行明细 `monthly_close_line` 0 行，符合「锁单前无明细」）；筛选 仓库/生成月份；行操作 查看 / 审批锁单。
- 写操作确认闸：`审批锁单` 前置 `ElMessageBox.confirm`（`WarehouseMonthlyCloseSheetsView.vue:730`）✅（本轮未提交）。
- 待深测：锁单后的行为（应禁止再改账期数据）需在 R11 造数时验。

### 页面：供应商对账单（`/warehouse/reconciliation`）｜（D28-B15 修复后首次进轮，R1 已证实可达）

- 页面形态：**表单页**（供应商 + 对账月份 → 「生成对账单」），未选时只显引导「选择供应商与月份后生成对账单」，无空表 ✅。
- 口径文案：「期初 + 本期新增应付 − 本期退货冲减 − 本期付款 = 期末。**只读聚合，不动任何资金**」——与财务口径的边界写清楚（生成不发钱）。
- 🔴 本轮**未点「生成对账单」**（写库）；`purchase_order` 2 张（`PO-DEMO-001` RECEIVED / `PO-UAT-B06` PENDING_APPROVAL）可用作 R11 的真单据样本。console 0 error。

### 页面：领货与调拨（`/warehouse/fulfillment`）｜选项卡：仓间调拨(0) / 出库单(14) / 已领未上柜(0)

- 数值对真值：**仓间调拨 0**（SQL `warehouse_transfer_line` 无在途单，空态「暂无调拨单」）；**出库单 14** = SQL `warehouse_outbound` 14 ✅（状态列 `DRAFT` 经字典 `warehouse_outbound_status` 显示为「待拣货」，字典核对一致，**不是裸码**）；**已领未上柜 0**，空态写明「补货员领货后出现于此；到柜确认上柜后自动消失」＝CB-074 语义 ✅。
- 写操作：确认拣货 / 作废出库 / 打印拣货单（只验入口，不提交）；工具栏 新建调拨/打印拣货单/确认拣货。
- 本页标题为「领货与调拨」且无「在途」段 ⇒ 与 CB-074（自提自送、无分仓→柜在途）一致 ✅。

### 页面：`/print` 打印单据（隐藏页，R4 必测）

- 入口：`openPrint(type, query)` → `router.resolve({ name:'print', query:{ type, ...query } })`；参数形如 `?type=purchase&purchaseOrderId=1`、`?type=picking&outboundId=15`。
- **内容对来源单据**（逐字段核）：
  - 采购收货单（PO 1）：打印显示 供应商 演示饮品供应商 / 仓库 演示中心仓 / 外部单号 `PO-DEMO-001` / 状态 已收货 / 1 行「可口可乐 330ml · BATCH-DEMO-001 · 采购 120 · 已收 120 · ¥1.80 · 到期 2027-04-05」；SQL `purchase_order_line` = ordered 120 / received 120 / unit_cost 180 / batch BATCH-DEMO-001 / expiry 2027-04-05 ✅ **完全一致**。
  - 拣货单（出库单 15，DRAFT→待拣货，2 行）：仓库 演示中心仓 / 状态 待拣货 / 2 行（康师傅冰红茶 3 件、加多宝凉茶 3 件，批次 B-WH-**-01，到期 2027-09-01）与 `warehouse_outbound` 15 + 2 行一致 ✅；页脚提示「请在拣货/领货时按单核对批次」。
  - 无参数直开 → 「暂无打印内容」+ 打印/关闭按钮（不白屏、不报错）✅。
- 🔴 **MA-013（P3，用户实测指出，已修）**：纸张固定 720px + 左右 40px 内边距，在 702px 视口溢出 ⇒ 横向滚动、看着贴左。改为 `box-sizing:border-box; width:min(720px, calc(100% - 32px))` + 窄屏收紧内边距；实测 702 → 670 宽两边各 16px、1600 → 720 宽两边各 440px，**均居中且无横向滚动**，打印媒体规则未动。
- 待真机/后续：真打印机出纸效果与「打印收货单」在收货流程内的联动（写操作）留 R11。

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
| P3 | 1 | MA-013（打印页窄视口居中，已修） |

## 需用户拍板

- （待填）

## 需用户配合（真机）

- 入库/出库/盘点若需走真机扫码或真柜签到，单列于此。

## 修复批次建议

- 本轮 **P0/P1 = 0** ⇒ 无轮末批次；MA-013 已在批 2 轮内修完（`1f18d352` + 产物 `a3aa3097`），带两个宽度的几何验证。
- 批 1、批 2 其余页零缺陷（多次探针误读已逐条排除并记录）。
- 待 R11 的写操作/真机项：新建盘点与上传解析、确认拣货/作废出库、生成对账单、审批锁单后的账期锁定、真打印出纸、索赔全链。

## 给下一轮的交接注记

- 新门禁 `check:list-filter-server-side` 已在 CI 生效（棘轮白名单 3 条）；R4 各页若出现「页内过滤」会直接红，按门禁提示改服务端化，不要往白名单里塞。
- 真值 SQL 注意逻辑删除：`sku_catalog.is_deleted`（R3 踩过）。
- 🔴 Element Plus 会保留非活动 tab 的 DOM：跨 tab 读 `el-pagination__total` / `el-table__empty-text` **必须加 `:visible` 或先取可见 pane**，否则会把仓库概览的分页当成货位的、把采购建议的空态当成采购退货的（R4 批 1 两次误报）。
- 🔴 有头 Playwright（`channel:'chrome'`）连开会堆积实例导致后续启动挂起；批量探针改用**无头**，需要肉眼观察时单独开一次有头并确保 `browser.close()` 执行到。
