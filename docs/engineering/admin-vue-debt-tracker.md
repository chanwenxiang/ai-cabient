# admin-vue 技术债追踪（2026-09-24 源码审计）

> **地位**：运营后台（`clients/admin-vue`）已知问题的**唯一进度表**。  
> **纪律**：每修完一项 → 把状态改为 `done` + 填「完成记录」→ 追加 `lessons-learned`（若成硬约束）→ `PROJECT_KNOWLEDGE` §9 一行。  
> **禁止**：只改代码不更新本表；用「感觉修好了」勾 done（须有门禁/命令或 diff 证据）。

**来源会话**：2026-09-24 admin-vue 全量接口梳理 + 细审。  
**总判**：鉴权/确认框/端点试点门禁在变好；主风险是上帝页、门禁盲区裸路径、soft-fail 伪装空数据、金钱 UI 无前端测。

---

## 状态图例

| 状态 | 含义 |
|------|------|
| `open` | 未开工 |
| `doing` | 进行中 |
| `done` | 已合入本仓并有完成记录 |
| `deferred` | 知情延后（须写原因） |

---

## 待办清单（按建议顺序）

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 | 完成记录 |
|----|--------|------|----------|----------|----------|
| D1 | P0 | done | 争议 + 补货开门裸路径未进 `AdminEndpoints`；门禁扫不到非 `/ops/admin` 前缀 | `DisputeListView.vue`、`ReplenishmentView.vue:2663`、`check-admin-endpoints.mjs` 仅扫 admin 字面量 | 2026-09-24：迁入 `disputes*`/`disputeSuggest`/`restockOpenDoor`；门禁 +3 字面量（共 66）；`node scripts/check-admin-endpoints.mjs` ok |
| D2 | P0 | done | 券 / 活动 / 公告 / 反馈裸路径同上 | `CouponsView`、`PromotionsView`、`AnnouncementsView`、`FeedbackView` | 2026-09-24：迁入 coupon*/promotion*/announcement*/feedback*；门禁 +4（共 70）；引用页含 PointsRedeem/UserAnalysis；`check-admin-endpoints` ok |
| D3 | P0 | done | 主路径 `.catch(() => [])` 把故障伪装成空数据 | `useWarehouseTabLoader.ts:61-71`、`DeviceDetailView.vue:1762+`、`BigScreenView.vue:766+`、`PrintView.vue` | 2026-09-24：新增 `softFallback`/`reportSoftFail`/`createSoftFailCollector` + 单测；四处改为可见 warning；大屏多路汇总一条 toast |
| D4 | P1 | done | `ReplenishmentView` 上帝脚本（~1720 行 script + `Record<string, any>`） | 行数实测；`type Row = Record<string, any>` ~L1353 | 2026-09-24：抽出 `useReplenishmentTaskActions`；script ~1720→~1560。**续拆 → D13** |
| D5 | P1 | done | 金钱 UI 无前端测（退款 / 争议 resolve / 提现） | admin-vue 仅 ~8 个工具层 `*.test.ts` | 2026-09-24：新增 `money-ui-contracts` + 8 测；Order/Dispute/MerchantWithdraw 写路径复用；`vitest` 8 passed |
| D6 | P1 | done | `WarehouseView` / `DeviceDetailView` 巨型模板难维护 | 模板 ~2221 / ~1351 行 | 2026-09-24：首刀 DeviceDetail 生命周期 composable+guards；~2750→~2580。**续拆 → D14** |
| D7 | P2 | done | 多处 `size=500/200` 全量下拉缓存 | `skusCatalogPage`、商户/设备下拉、券 definitions | 2026-09-24：`admin-catalog-query` 常量+助手；Endpoints/`merchantsCatalog`/`devicesOptions` 收口；views 去魔法 size；3 测 |
| D8 | P2 | done | 仓配/补货/风控弱类型 `Record<string, any>` | `composables/warehouse/*`、`RiskView`、`FeedbackView` | 2026-09-24：首刀 Feedback/Risk 接 DTO。**仓配·补货 any → D15** |
| D9 | P2 | done | 2FA challenge 进 `localStorage` | `stores/auth.ts` `TWO_FACTOR_KEY` | 2026-09-24：改 `sessionStorage` + 清遗留 localStorage；logout 同步清 |
| D10 | P3 | done | `admin_permissions` 只写不读（死写） | `auth.ts` `PERM_KEY` | 2026-09-24：停写 PERM/NAV 缓存，只 `clearLegacyRbacCache`；权限仅内存+服务端 |
| D11 | P3 | done | 死端点镜像 `dataTables/schema/row/create/update` | `AdminEndpoints`；前端仅用 capabilities+delete | 2026-09-24：删除 `dataTables`/`dataSchema`/`dataRow`/`dataCreate`/`dataUpdate`；保留 capabilities+delete |
| D12 | P3 | done | API 前缀分裂（admin / ops / coupons / public）文档化不足 | 多前缀并存；测试脚本曾路径搞混 | 2026-09-24：`endpoints.ts` 文件头前缀表 + 门禁指针；Changelog 索引 |

---

## 二期后续（D1–D12 首刀遗留，2026-09-24 开单）

> 来源：D4 / D6 / D8 完成记录中的「仍可后续再拆 / 延后」。  
> **纪律同表头**：修一项 → `done` + 完成记录 → Changelog；禁止只聊天不改本表。

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D13 | P2 | done | 补货页继续拆：明细抽屉 / 要货流（及剩余 script） | 承接 D4；`ReplenishmentView` 仍大；已有 `useReplenishmentTaskActions`，禁止新柜门逻辑塞回 View | 2026-09-24：抽出 `useReplenishmentRequestFlow` + `useReplenishmentTaskLines`；View ~3016→~2764；`vue-tsc` 绿 |
| D14 | P2 | done | 仓配巨型模板再拆；设备详情货道/温控/盘点等继续抽 composable | 承接 D6；Warehouse 已有 dialogs+composable，模板 pane 仍肥；DeviceDetail 生命周期已抽，slots/temp-env 未抽 | 2026-09-24：首刀 `useDeviceTempEnv`；DeviceDetail ~2662→~2570。**货道编辑 → D16** |
| D15 | P2 | done | 仓配 / 补货行类型去 `Record<string, any>` | 承接 D8；`composables/warehouse/*`、`ReplenishmentRow`、`WarehouseView`/`ReplenishmentView` 的 `Row`；有 OpenAPI 则接 DTO，动态列须注明例外 | 2026-09-24：`AdminDynamicRow` 集中逃逸；仓配/补货 View+composables 改用；补货柜门写路径→`OpenApiReplenishmentTaskDto`/`RouteDto`；顺带恢复 tabLoader softFallback |

**建议顺序**：D15（类型，改动面可控）→ D13（补货业务写路径）→ D14（模板体积，易冲突，宜小切片）。

---

## 三期后续（D14 首刀遗留，2026-09-24 开单）

> 来源：D14 完成记录中的「货道编辑与 Warehouse 模板 pane 仍可再拆」。

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D16 | P2 | done | 设备详情货道编辑/盘点抽出；仓配模板 pane 视需要再拆 | 承接 D14；货道写路径（套模板/刷新/编辑/盘点/保存）曾埋在 SFC；Warehouse 已有 dialogs+composable | 2026-09-24：`useDeviceSlotActions`；DeviceDetail ~2570→~2413。**仓配 pane → D17** |

---

## 四期后续（D16 遗留仓配模板，2026-09-24 开单）

> 来源：D16 / D14 注明的 Warehouse 巨型模板 pane。

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D17 | P2 | done | 仓配 `WarehouseView` 巨型模板按 Tab 拆子组件 | 模板曾 ~3200+；dialogs 已外置，pane 仍堆在 View | 2026-09-24：首刀 `WarehouseSuggestionsTab` + `WarehousePayablesTab`；View ~3242→~2929。**采购单/出库单 → D18** |

---

## 五期后续（D17 遗留大 pane，2026-09-24 开单）

> 来源：D17 完成记录中的「采购单/出库单等仍可再拆」。

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D18 | P2 | done | 仓配采购单 / 出库单 Tab 拆子组件 | 承接 D17；`purchase` ~222 行、`outbounds` ~201 行，含 expand+写动作；边界：父级保留审核/收货/拣发运 composable，子组件只渲染+emit | 2026-09-24：`WarehousePurchaseOrdersTab` + `WarehouseOutboundsTab`；View ~2929→~2550。**其余 pane → D19** |

**建议顺序**：采购单（采购域，与已拆建议/应付同组）→ 出库单（履约域，含 data-testid）。

---

## 六期后续（D18 遗留 pane，2026-09-24 开单）

> 来源：D18 完成记录中的「调拨/在途/货位等若再拆」。

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D19 | P2 | done | 仓配调拨/退货/盘点/货位 Tab 拆子组件 | 承接 D18；`transfers`/`returns`/`stocktakes`/`bins` 曾在 View | 2026-09-24：`WarehouseTransfersTab`/`PurchaseReturnsTab`/`StocktakesTab`/`BinsTab`；View ~2550→~2070；在途/批次/流水/概览/供应商 → D20 |

---

## 七期后续（D19 遗留 pane，2026-09-24 开单）

> 来源：D19 完成记录中的「在途/批次/流水/概览/供应商若再拆」。

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D20 | P2 | done | 仓配概览/供应商/在途/批次库存/流水 Tab 拆子组件 | 承接 D19；`warehouses`/`suppliers`/`transit`/`inventory`/`movements` 曾在 View；写动作仍在父级 | 2026-09-24：`WarehouseOverviewTab`/`SuppliersTab`/`TransitTab`/`InventoryTab`/`MovementsTab`；View ~2070→~1665；仓配 Tab 表格已全部外置 |

**建议顺序**：概览（CrudTable）→ 供应商（排序）→ 在途 → 批次库存 → 流水。

---

## 八期后续（设备详情续拆，2026-09-24 开单）

> 来源：D16 后 DeviceDetail 仍肥；仓配 D20 清完后回拆设备页最大卡片。

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D21 | P2 | done | 设备详情「资产与投放」抽 composable+卡片 | 承接 D16；资产 PATCH / 地址 / 生命周期按钮条曾埋 View；生命周期写路径仍走 `useDeviceLifecycleActions` | 2026-09-24：`useDeviceAsset` + `DeviceAssetDeploymentCard`；DeviceDetail ~2412→~2004；关联单据 → D22 |

---

## 九期后续（D21 遗留关联单据，2026-09-24 开单）

> 来源：D21 完成后 DeviceDetail 仍肥；关联单据 tab 边界最干净。

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D22 | P2 | done | 设备详情「关联单据」抽 Tab+loader | 承接 D21；sessions/orders 列表与 softFallback 曾埋 View；导航仍走 `goPath` | 2026-09-24：`useDeviceRelatedRecords` + `DeviceRelatedRecordsTab`；DeviceDetail ~2004→~1767；远程运维 → D23 |

---

## 十期后续（D22 遗留远程运维，2026-09-24 开单）

> 来源：D22 完成后 DeviceDetail 仍肥；远程运维卡含指令/退款/策略锁/维修。

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D23 | P2 | done | 设备详情「远程运维」抽 composable+卡片 | 承接 D22；`sendCommand`/退款/策略锁/维修曾埋 View；`cmdLoading` 与温控 SET_TEMP 共用 | 2026-09-24：`useDeviceRemoteOps` + `DeviceRemoteOpsCard`；DeviceDetail ~1767→~1351；温控 Tab UI → D24 |

---

## 十一期后续（D23 遗留温控 Tab UI，2026-09-24 开单）

> 来源：D23 后 DeviceDetail 温控计划/环境监控 markup 仍在 View；逻辑已在 `useDeviceTempEnv`。

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D24 | P2 | done | 设备详情温控 Tab UI 抽子组件 | 承接 D23；`useDeviceTempEnv` 已有，markup 未外置；首屏即时设温仍留 hero | 2026-09-24：`DeviceTempEnvTab`；DeviceDetail ~1351→~1255 |

**风险记录**：2026-09-25 对 `ReplenishmentView` 的规划对话框切片误用破坏性 patch + `git checkout`，**工作区未提交的 D13 View 接线被回退到 HEAD（~3378）**；`useReplenishment*RequestFlow/TaskLines/TaskActions/RoutePlanning` 文件仍在磁盘。须另开单 **D25** 恢复 D13 接线后再抽规划对话框。

---

## 十二期后续（补货 View 恢复，2026-09-25 开单）

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D25 | P1 | done | 恢复 `ReplenishmentView` D13 接线；再抽规划路线对话框 | View 曾回退 HEAD~3378；composable 文件仍在；已预写 `useReplenishmentRoutePlanning` + `ReplenishmentPlanRouteDialog` | 2026-09-25：恢复 TaskActions/RequestFlow/TaskLines；接线规划对话框；View ~3378→~2468 |

**建议顺序**：先恢复 TaskActions/RequestFlow/TaskLines 接线并 `vue-tsc` → 再接线规划对话框。

---

## 十三期（开门柜「货道」概念残留，2026-10-28 开单盘点 · 2026-10-29 A 类落地）

> **判据（2026-10-28 用户拍板，勿再自创）**：开门柜 = `jiangyi_device.status = BOUND`（**不是** `device_type`）。
> 开门柜没有「货道」概念：补货任务行 `replenishment_task_line.slot_id` 恒为 `null`，在售面读
> `class_mapping + sku_catalog + inventory`（见 `PROJECT_KNOWLEDGE` 将邑段 / V400 映射复核闸口）。
> 货道柜（重力 / 闸机）仍有 `device_slot`，那部分「货道」是真实概念，**不在本债内**。
>
> **2026-10-28 已动的一处**：`views/growth/ReplenishmentStaffView.vue` 理货记录删掉 `prop="slotId" label="货道"` 列
> （详见修复日志 D26）。其余按下表分类，**2026-10-29 A 类 8 处已改**，B/C 类保留。
>
> **口径**：`grep -rn 货道 clients/admin-vue/src`（含 `.ts` / `.vue`，不含 `static/` 构建产物）＝ 84 处 / 20 文件。
>
> **2026-10-29 追改（用户拍板「A 类 8 处一起改」）**：A 类按下表 ✅ 落地——4 处**删列** + 4 处**改词**，
> `ReplenishmentView` 的 8 处**未动**（并入「缺货新口径」那笔）。改后 `grep -o 货道` 由 **96 处 / 21 文件
> （HEAD `8c74cfd2`）** 降到 **86 处 / 17 文件**，剩余全部是 B 类、C 类与 `ReplenishmentView`。
> 口径变了要留意：2026-10-28 记的 84/20 是**当时**的树；此后 peer 的盘点等提交又新增了 12 处。

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D26 | P2 | closed（2026-10-30 查证收口） | 运营后台仍有文件对**开门柜**讲「货道」：恒空列 / `待分配`·`无`·`暂无` 兜底 / 按货道粒度的文案 | 下表 A 类逐条 `file:line`；判据来自 2026-10-28 拍板 | 2026-10-29：A 类 8 处落地（见下「处置」与修复日志）。2026-10-30：余项 `ReplenishmentView` 8 处**查证为 no-op**——后端 `DeviceSlotService.resolveDevicesForShortage:357/:363` 与 `listDiscrepancyAlerts:258/:268` 已把开门柜整类排除出「缺货建议」行源，`unassignedRestockByTask:378` 对开门柜不计数，本地 BOUND 柜 `device_slot` 实测 0 行 ⇒ 那些「货道」字样只会作用在货道柜行上，措辞诚实，**隐藏货道列那一档作废**。同轮查出并修掉一条真缺陷（效期 tab 补货按钮被 `restockHeadroom` 恒 0 锁死而后端放行），见 `fa03ce1c`。2026-10-31 追加：用户拍板「货道柜应禁用，我们系统没有货道了」⇒ 效期 tab 的货道柜反证作废（钉测即终态）；同时按同一口径改写本页**两处措辞**——tab 描述（现 `:878`）与空态（现 `:585`）明确「本表只列货道柜的货道行，开门柜缺货请看『库存健康』」。原因是这张表的行**只能**由 `device_slot` 构造（`ReplenishmentService.listShortagePage:326` → `DeviceSlotService.listShortageRows`，三处排除开门柜 `:268`/`:357`/`:363`），车队只有开门柜后该 tab 恒空、`一键规划补货` 按钮因 `v-if="canEdit && shortageDevices.length"` 永不出现，而旧描述语还在承诺「各柜机低于安全库存的商品，可一键生成补货规划」。**能力未丢**：`views/reports/StockHealthView.vue` 走台账 + `SkuStockLevel`（`:346`/`:516`），不排除开门柜，其 `一键补货规划（N 台）`（`:18`）是现役入口。本页其余「货道」字样仍按「对实际出现的行诚实」保留，隐藏货道列那一档仍作废 |

### A 类 · 对开门柜会误导（✅＝2026-10-29 已改；`ReplenishmentView` 待缺货新口径）

| 位置 | 现状 | 为什么说它误导 |
|------|------|----------------|
| ✅ `components/warehouse/WarehouseOutboundsTab.vue:84` | `label="货道"`，单元格 `{{ row.slotId \|\| '无' }}` | 出库明细行=补货任务行；开门柜恒 `null` ⇒ 整列恒显「无」 |
| ✅ `views/print/PrintView.vue:35` `:53` | 拣货单表头「货道」+ `{{ line.slotId \|\| '待分配' }}`；脚注「请按单核对批次与货道」 | 开门柜的货**永远不会分配货道**，「待分配」把结构性缺失说成流程未完成 |
| ✅ `views/orders/OrderListView.vue:181` `:626` `:812` | 商品列内嵌货道展示 + 明细 `prop="slotId" label="货道"`（`\|\| '暂无'`） | 将邑柜订单走识别结算，无 slot；`utils/order-line-summary.ts:9` 靠后端 `·货道A1` 字面量解析，开门柜订单串里根本没有该段 |
| ✅ `views/disputes/DisputeListView.vue:587` | 争议明细 `prop="slotId" label="货道"`（`\|\| '暂无'`） | 同上，订单行的 slot 对开门柜恒空 |
| `views/replenishment/ReplenishmentView.vue:382` `:576` `:595` `:738` `:946` `:952` `:1121` `:1600`（行号系 2026-10-29 快照，现已漂移） | 补货建议表 `prop="slotCode"`、CSV 表头「货道」、「货道已满请先下架」、确认文案「设备×货道 粒度」；~~空态「当前无缺货/低库存货道」~~、~~tab 描述「各柜机低于安全库存的商品…」~~（这两处 2026-10-31 已改，见上 D26 追加） | 2026-10-28 已拍板：开门柜补货行恒 `slotId=null`，与残留货道无关 ⇒ 这些把「货道」当补货单位的表述对将邑柜不成立（**缺货新口径**本身也在待拍板清单里） |
| ✅ `views/analytics/FootfallView.vue:8` `:189` `:225` | 页标题与模块标题「货道热区」，空态「暂无带货道明细的订单」 | 热区按货道坐标聚合；开门柜订单无坐标 ⇒ 模块对该柜型天然为空，需在标题上限定「（货道柜）」而非留白 |
| ✅ `components/device/DeviceJiangyiCard.vue:14` `:481` | 将邑柜卡片自己写着「库存走既有『货道管理』」「上架；库存走『货道陈列』」 | 🔴 最刺眼的一处：正在被当开门柜用的组件里教运营去找货道 |
| ✅ `views/growth/SkuReviewView.vue:9` `:290` `:295` `:324` `:329` | 下架提示「仅备注，不改货道」 | 纯文案，但把「柜内商品」说成「货道商品」；改词为「不改柜内在售商品」即可 |
| ✅ `views/vision/LearningLibraryView.vue:89` `:130` | 「货道在售 ≠ 识别上架」 | 将邑映射页；开门柜的「在售」不是货道，措辞与 V400 映射口径混在一起容易读成两套 |

**2026-10-29 处置（8 处落地，`ReplenishmentView` 除外）**：
- **删列 4**：`WarehouseOutboundsTab` 出库明细「货道」；`PrintView` 拣货单「货道」表头 + `<td>`（`待分配` 兜底随之消失）+ 脚注去掉「与货道」；`OrderListView` 主表「货道」列 **与** 明细行「货道」列（顺带删掉只为该列存在的 `goodsDisplay().slots`，`parseGoodsLines`/`goodsDisplay` 保留给商品列）；`DisputeListView` 明细行「货道」。
- **改词 4**：`DeviceJiangyiCard` → 「库存由补货/理货流程维护」；`SkuReviewView` → 「不改柜内在售商品」；`LearningLibraryView` → 「柜内在售 ≠ 识别上架」；`FootfallView` → 「货道热区（仅货道柜）」+ 空态「开门柜订单不含货道坐标，此处无数据」。
- 删列处留了一行 `<!-- 无「货道」列：… -->` 注释（含「货道」二字），所以 `grep 货道` 在这些文件里仍有 1 处——**这是留痕不是残留**，下次盘点勿当漏网。

### B 类 · 货道柜固有概念，保留（勿顺手删）

| 位置 | 说明 |
|------|------|
| `views/devices/DeviceDetailView.vue`（19 处中的 KPI / 编辑货道弹窗 / 模板套用 / 排序） | 该页已按 CB-051 改造：`:440` `:1390` 明确「开门柜无货道概念、在售商品=商品卡片，卡片即编辑入口」。剩余「缺货货道 / 低库存货道 / 编辑货道 / N 货道」服务货道柜，且 `:735` 弹窗只在有 slot 的行入口出现 |
| `composables/device/useDeviceSlotActions.ts`（7 处）· `components/device/SlotInlineRow.vue`（2 处） | 货道行内编辑的实现本体，同上一并保留 |
| `views/consistency/ConsistencyView.vue:401` | `SLOT_CAPACITY` 巡检项的中文标签，名字等于后端 check code，不是界面自造概念 |
| `views/merchants/MerchantSplitsView.vue:204` `:213` `:214` `:982` | `allowMerchantPlanogramEdit`（flag `planogram`）商户能力开关：对只跑开门柜的商户自然失效，删开关要动后端字段，属另一笔账 |
| `utils/order-line-summary.ts:2` `:3` `:9` `:25` + `.test.ts`（7 处） | 解析 `·货道A1` 的唯一实现与其单测；货道柜订单要它 |

### C 类 · 注释 / 关键词 / 端点说明，保留

`api/endpoints.ts:106` `:155` `:360`、`config/menu.ts:300`（菜单搜索词，删了搜不到）、`views/skus/SkuListView.vue:322` `:377` `:525`（件装数/上架限额/「本项目无称重货道」注释）、`views/analytics/FootfallView.vue:265`（竞态注释）、`views/devices/DeviceDetailView.vue:834` `:838` `:2290`（历史对照注释）。

**待拍板（三项 → 2026-10-29 已拍板 1、2；2026-10-30 第 3 项查证闭合）**：
1. ~~A 类「恒空列」是删列还是按柜型条件显示~~ → **拍板删列**：这批界面服务的柜机固定都是将邑开门柜（用户 2026-10-28 定），不需要行级柜型判据，也就不存在「货道柜仍展示」的分支。
2. ~~`PrintView` 的「待分配」兜底词~~ → 随删列消失，不再需要选「不适用」还是条件显示。
3. ~~`ReplenishmentView` 的补货建议是否只对货道柜显示货道列~~ → **不用改，且改不了**：这张表的行源里根本没有开门柜（后端两处排除 + `device_slot` 0 行实证），「只对货道柜显示货道列」是恒真命题。同轮查出该页**唯一**对开门柜可达的货道门在「效期」tab：`expiryRestockEnabled` 只看 `PullOffTaskDto.restockHeadroom`，而该值走 `allocateRestockQuantity` 遍历 `device_slot` ⇒ 开门柜恒 0 ⇒ 按钮永久置灰并弹「货道已满」，后端 `preparePullOffTaskPlan` 却放行。已按 CB-051 同一结论修：柜型标志由服务端单源下发（`doorCabinet` = `isDoorCabinet` = 将邑 BOUND），前端不另立判据（尤其不得改用设备详情那个含 RETIRED 的 `binding != null` 口径）。

---

## 十四期（仓储拆页后「渠道对账导入」tab 掉了，2026-10-29 开单）

> 来源：重新生成 `docs/ADMIN_TABLE_INVENTORY.md` 时由扫描器算出「表格有代码但路由上到不了」，
> 只有一条，人工逐行复核成立。非本轮改动引入，属 `f030f09f` 仓储拆页遗留。

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D27 | P2 | closed（2026-10-29） | 「渠道对账导入」整块 UI 从侧栏进去不到了：6 张表（外部渠道商品映射 / 批次 / 流水 / 批次明细等）仍编译进包，但没有任何页面渲染它 | ①`views/warehouse/WarehouseView.vue:575-581` 的 `<el-tab-pane name="channel-import">` 受 `isPaneVisible()` 门控；②`isPaneVisible` 在 `props.only` 模式下等价于 `only.includes(name)`（`:1007-1008`）；③四个子页的 `:only` 分别是 `master=[warehouses,bins]`、`procurement=[suppliers,purchase,suggestions,returns,payables]`、`stock=[stocktakes,inventory,movements,monthly]`、`fulfillment=[transfers,outbounds,transit]` —— 都不含 `channel-import`；④`router/index.ts:13` 的 `ROUTE_TAB_SUB` 也没有该 tab（旧 `/warehouse?tab=channel-import` 会落到 master 再被钳回 `warehouses`）；⑤`config/menu.ts` 无对应入口；⑥`:585` 仍专门为 `channel-import` 保留「隐藏筛选栏」分支 ⇒ 拆页时是**漏配子集**，不是刻意下线 | 用户拍板「按你的推荐」⇒ 落 **`/warehouse/stock`**：`ONLY_STOCK` 补 `channel-import`（`WarehouseStockPage.vue:7`），`ROUTE_TAB_SUB` 补 `channel-import: stock`（旧深链 `?tab=channel-import` 直接落到库存页而不是 master 再被钳回）。依据不只是「与批次库存同组」（`TAB_GROUP_ORDER.inventory` 本就含它），更硬的一条是组件自述：提交后聚合成 `external_channel_flow`，**月结按「账期+仓」取「仓库外调/客情用水」列**（`ExternalChannelImportTab.vue` 头注释，CB-036/V347）⇒ 它喂的就是同页的月结 tab。盘点文档重生成后 `/warehouse/stock` 由 9 表/73 列变 **15 表/113 列**（新增 6 张全来自该 tab） |

---

## 十五期（仓储/履约三端口径复审，2026-10-29 开单 · 同日用户拍板「按推荐顺序修」）

> **来源**：用户 2026-10-29「仓储和履约仓储模块还有商户端重新审计一遍，口径和页面都有很大的问题呀」⇒ 三路
> 只读子 agent 报告 + 逐条实读复核，产出 **A1–A9 口径冲突 / B1–B14 页面缺陷 / D 待拍板五项**。
> 拍板结论：「按照你的推荐顺序完成修复吧」（F1→F5）＋**业务模型口径**：「补货员是异地的，他们有自己的仓库，
> 只有采购我们才会有什么在途……所以应该没有在途啥的」⇒ 分仓→柜**无运输段**，差额必须显式落在单据上。
> 台账见 [COMPETITOR_BENCHMARK.md](../COMPETITOR_BENCHMARK.md) **CB-074**（差额=单据事实，不是库存事实）。

### 修复批次 F1–F5（对应关系）

| 批次 | 覆盖缺陷 | 落点 |
|------|----------|------|
| **F1** | A1（差额黑洞 + `Integer.MAX_VALUE` 伪签收）、A2（月结「上柜」列取领货量） | 出库行 `warehouse_outbound_line.remainder_qty`（[V414__outbound_line_remainder_qty.sql](../../services/trade-service/src/main/resources/db/migration/V414__outbound_line_remainder_qty.sql)）＋ 按 SKU 实收分摊（`markDeviceHandoverReceived`）＋ 月结三列「领货·上柜·领而未上」（`shelvedQty = qty − remainderQty`）；门禁 `check:outbound-remainder-ledger` + 负向验证 |
| **F2** | A3（三端各讲一套「发运/签收/在途」）、B1（标题/hint 恒一句）、B2（子页无同级导航） | 仓→柜链路用户面下线旧叙事（tab「已领未上柜」、按钮「确认领货」、菜单与路由同名「领货与调拨」、四子页各自 `hint`、`warehouse-sibling-nav`）；字典标签三处同改（`shared-dict` src+dist / `SysDictBootstrap` / [V415__warehouse_selfpickup_dict_labels.sql](../../services/trade-service/src/main/resources/db/migration/V415__warehouse_selfpickup_dict_labels.sql) 的 12 条带旧值锚点 UPDATE）；门禁 `check:selfpickup-copy` + 负向验证（31 条注入）。**保留**：`warehouse_in_transit` 机制层、跨仓调拨段的发运/收货/在途损耗口径 |
| **F3** | A7（「待分配」把开门柜正常任务当异常）、A8（出库货道封顶无 `!isDoorCabinet` 守卫、超限行 `deleteById`） | 徽章与「仅待分配」筛选按开门柜豁免；`clampLinesToSlotHeadroom` 加柜型守卫 |
| **F4** | B3（「全部仓库」实为默认仓）、B11（5 个流水类型字典缺失）、B12（`handover/in_transit` 缺 `CANCELLED`） | 见下表逐条 |
| **F5** | B9（权限码不配对 ⇒ 前端能点、后端 403）＋ 其余低风险页面缺陷 | **按后端码收紧前端**（放宽后端＝给 `warehouse:edit` 开报损权，用户已认同零资金风险方向） |
| **A5 轮**（F5 之后同日拍板） | D28-A5 ＋ B3/B5 残留 | 拍板四件：B3/B5 走隔离栈 regen、A4 先诚实化、**A5＝mp 前端对齐后端**、A6＝统一 SKU 级、A9＝复核后取更严。A5 落点见下表 D28-A5 完成记录 |

### 本期状态表

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 / 边界 | 完成记录 |
|----|--------|------|----------|-----------------|----------|
| D28-A1 | P0 | closed（F1） | 补货结案单据传 `Integer.MAX_VALUE` ⇒ 单据恒「已签收」、`PARTIAL` 不可达，**领货−实收的差额无主**（仓已扣、柜未入、无报损无差异） | `WarehouseService.receiveOutboundHandover`（改前 `full = appliedQty >= shipped` 恒真）；铁律 78/79 | 2026-10-29：差额落 `remainder_qty` ＋ 满量 `RECEIVED`/未满 `PARTIAL`；⛔ 禁止把差额补记柜机库存（铁律 78）、禁止再写库存流水（铁律 79） |
| D28-A2 | P0 | closed（F1） | 月结「上柜」列实际取 `OUTBOUND_SHIP` **领货量** ⇒ 少上架时「应有」偏低、月末**虚盈**，正好掩盖 A1 | `WarehouseMonthlyCloseMath`（改前按发运量入 `shelved`） | 2026-10-29：`shelvedQty = qty − remainderQty`，下钻明细合计 ≡ 列值；月结 DTO 加 `shelvedQty` |
| D28-A3 | P1 | closed（F2） | 铁律 80 说「自提自送无签收」，但 A 端仍有「确认发运」+ 独立「在途」页 + 24h 超时红标，商户端教用户「签收在途商品」 | `WarehouseView`、`SysDictBootstrap`、mp `useReplenishmentFulfillment` / `prep-generate` | 2026-10-29：三端文案+字典+迁移三处同改，门禁 `check:selfpickup-copy` 钉住 |
| D28-A4 | P2 | closed（2026-10-30，拍板「先诚实化」） | 货位账只由 `WarehouseBinService` 写；采购收货/其他入库/发运/调拨/核销/盘点**都不动 bin** ⇒ 货位库存页合计 ≠ 批次库存页，且移库不写流水、流水页追不到 | `WarehouseBinService:28` 类注释「货位账与仓库账一致」名不副实 | 2026-10-30：**落诚实化档**（全链路写 bin 仍未拍板，见下）。①类注释撤掉「保证货位账与仓库账一致」，改写成三条真实边界：只有 `inboundToBin` 同步批次账 + 记一笔 `BIN_INBOUND` 流水；其余总账增减（采购收货 `receivePurchaseStock:266`、手工入库 `inbound:230`、`shipOutbound:845`、跨仓调拨 `WarehouseTransferService`、报损核销、盘点 `adjustStocktake:329`）一律不写货位账——`WarehouseBinStockMapper` 全仓只有本类持有；`moveBetweenBins` 既不动总库存也不写流水 ⇒ 流水页追不到移库。②`WarehouseView.vue` bins 页 `TAB_DESC` 旧文案「出入库单据会引用」**全仓无出处**（`grep bin_id` 只命中 V161/V237 建表与索引，没有任何单据表引用货位）⇒ 改成「独立台账…本页合计 ≠ 批次库存，不能拿来对账」。③两条钉住边界的**能红**新测（`WarehouseBinServiceTest` 现 9 例）：`moveBetweenBins_leavesWarehouseAggregateUntouched`（`verify(never()).binStockChange`）＋ `stockWritingServicesHoldNoBinLedgerDependency`（反射扫 `WarehouseService`/`WarehouseTransferService`/`InventoryOpsService` 字段，任何一方将来持有 `WarehouseBin*` 依赖即红，红信息点名「同步删掉注释与文案里的『不写货位账』表述」）。④铁律 75 红证：两份生产文件各注入反向改动 ⇒ `Tests run: 9, Failures: 2`（`NeverWantedButInvoked: warehouseService.binStockChange(…BinStockChangeCommand)` ＋ `InventoryOpsService 新持有了货位依赖 WarehouseBinMapper…`）⇒ 逐字节复原后工作树 sha256 `25e1063efd4a…8dc7a1b70` 与注入前记录值一致 ⇒ 因共享工作树此刻被 peer 的 CB-076 在途重构（`SkuStockLevel`）弄到编译不过，改在**隔离 worktree**（HEAD `0559f662` ＋仅本批 4 文件）复跑 `WarehouseBinServiceTest` **9/0** ＋ `DeviceSlotServiceTest` **13/0**、BUILD SUCCESS、surefire 报告 mtime 09:11。⑤未动：把 6 个总账写入点接上货位账（需同时改 `WarehouseView` 文案并删掉这条边界测）——**仍未拍板**，本轮只让页面与注释不再撒谎 |
| D28-A5 | P2 | closed（2026-10-29，拍板「mp 前端对齐后端」） | 同一 SKU 三个数：建议量扣「全部在途」、提交封顶扣「排除本单在途」、mp 前端封顶**再减一层不扣在途** | `WarehouseService:408`（建议量，另一层语义，未动）、`ReplenishmentService.doorCabinetHeadroomBySku`（唯一出处）、mp `useReplenishmentFulfillment.increaseLineQty` | 2026-10-29：**服务端算一次、下发、前端只读**。①`ReplenishmentService.taskHeadroom(task)` + `GET /merchant/replenishment/tasks/{taskId}/headroom`（[ReplenishmentHeadroomDto.java](../../services/common/common-core/src/main/java/com/aicabinet/common/dto/ReplenishmentHeadroomDto.java) 四要素 `capacity/onShelfQty/otherInTransitQty/room`），提交校验 `assertDoorCabinetHeadroom` 复用同一 Map ⇒ 读到的就是能提交的。②货道柜恒空数组（上限来自 `device_slot`，不把台账水位当第二套上限）；`capacity≤0` = 未配容量 ⇒ fail-open，与后端同判据。③契约：隔离栈 regen，`openapi.ts` **+65 / −0**（8 处 headroom），别名 `OpenApiReplenishmentHeadroomDto`。④mp 单一算法 [replenishment-headroom.ts](../../clients/merchant-mp/src/utils/replenishment-headroom.ts) `doorHeadroomForSku()`：server 优先、台账 `capacity−在架` 降级（偏松一档，判官仍在提交侧），提示文案与后端 400 **同字面**（`describeDoorHeadroom`）；两个站点（录入 `+` / 详情收敛 watcher）都改走它。铁律 75 红证：后端 `ReplenishmentHeadroomReadMatchesSubmitGateTest` 8/8（含「读到 N 能提交 N、N+1 必拒且四项数字逐字相等」）＋两次注入红→sha256 复原；mp `useReplenishmentFulfillment.door-cabinet.test.ts` 12/12（新增 5 例），把 `doorHeadroomForSku` 临时改成台账优先 ⇒ 3 红（`expected 10 to be 5` / `2 to be 8` / `4 to be 2`）⇒ 逐字节复原（sha256 `186d245f…`）⇒ 12/12 绿。mp 全量 vitest 216/216、`vue-tsc --noEmit` exit 0、`build:mp-weixin` release exit 0（`dist/build/mp-weixin/utils/replenishment-headroom.js` 含新字面量、server 优先逻辑在产物里）。门禁 `check:openapi-types` / `check:openapi-regen` / `check:merchant-endpoints` / `check:shared-dist-fresh` / `check:mp-*` / eslint 全 0 |
| D28-A6 | P2 | closed（2026-10-29，拍板「统一 SKU 级」） | 账面量判据两套：库存健康按**设备级**批次（有任一 lot ⇒ 未进账 SKU 读 0），建议按 **SKU 级**且注释明文禁止设备级 ⇒ 两页数字当面打架 | `DeviceAssetService.effectiveSellableQty`（改前 `ledgerByDevice = !lotMapper.findByDeviceId(d).isEmpty()`）、`WarehouseService.bookQtyForSuggest`（改前已是对的参照） | 2026-10-29：**判据统一到 SKU 级**——`lotLedgerSkuIds(deviceId)` 含本 SKU ⇒ 账本可售（卖光/过期=0 是事实），不含 ⇒ 回退 `device_sku_inventory.quantity`。①库存健康 `DeviceAssetService` 改走 `inventoryLotService.lotLedgerSkuIds` + `sellableQtyBySku`（一次查询，无 N+1），并在 javadoc 明文禁止设备级一刀切；②**顺带收口同类两处**（同一缺陷形态，商户端/运营台定价页的「库存」列）：`MerchantSkuPricingService.resolveSellableQtyBySku` + `updatePricing`、`OpsDevicePricingService.resolveSellableQtyBySku`。铁律 75 红证：把改后的 SKU 级判断临时换回设备级 ⇒ `StockHealthSkuLevelBookQtyTest` **2 红**（`expected <LOW> but was <STOCKOUT>` + 假缺货行出现）、定价两侧各 **1 红**；三份源文件 sha256 逐字节复原（`6dac6438…`/`c37c0407…`/`f673eb0d…`）后复跑 4/0 + 3/0 + 6/0 全绿。相邻 10 个测试类（87 例：Clearance/NearExpiry/TimeWindow 定价、DeviceCatalog、Jiangyi 两处、Merchant 三处、Ops 工作台）全绿。⚠️ 只改读路径，未动 `deviceUsesLotLedger` 的写入侧语义（`InventoryLotService` 建账判定不变） |
| D28-A7 | P1 | closed（F3） | 「待分配」＝`applied=false 且 slot_id 空`，而开门柜补货行**恒** `slot_id=null` ⇒ 徽章常亮、筛选命中全部开门柜（商户端已豁免，A 端没有） | `ReplenishmentService:356` vs `:1046`；`ReplenishmentView.vue:175` | 2026-10-29：A 端按开门柜豁免，与 mp `ReplenishLinesSection.vue:67` 同判据 |
| D28-A8 | P1 | closed（F3） | 出库封顶只判 `hasSkuSlots`，无 `!isDoorCabinet` 守卫 ⇒ 超限行直接 `deleteById`，出库行数量「莫名变小」、备货清单偏低 | `WarehouseService:1323/1350/1355` | 2026-10-29：加柜型守卫，开门柜不再按死数据 `device_slot` 截断 |
| D28-A9 | P2 | closed（2026-10-29，拍板「复核后取更严」） | 商户归属两套判据（`requireDevicePack` FIELD 包 vs `canAccessDevice` 数据范围），且 `MerchantPortalController` 注释写「仅分仓负责人可用」**与实现不符** | `MerchantReplenishmentService.doSelfInitiateTask:361` vs 其余 9 处 `requireDevicePack(FIELD)`（`:92/:202/:216/:285/:305/:333/:513/:523/:534`） | 2026-10-29：🔴 **复核后方向订正**——不是「预览说能补、点生成被拒」，而是**反过来**：发起腿是全链路唯一只判裸 `canAccessDevice` 的入口 ⇒ 未开 FIELD 包的商户名下柜机**能扣分仓库存生成出库/补货任务，却看不到建议/明细**（会动库存的写腿比只读的读腿还松）。落法=发起腿补 `requireDevicePack(FIELD)`；`MerchantPortalController:766` 假注释订正为真实四条判据（旧口径「仅分仓负责人」是 CB-068 A 档 2026-10-27 放宽前的残留）。红证：删掉该行 ⇒ `MerchantSelfReplenishTest` **2 红**（`Wanted but not invoked: requireDevicePack` + `expected:<403 FORBIDDEN> but was:<400 BAD_REQUEST>`）⇒ sha256 `b513b603…940af` 复原 ⇒ 17/0；商户+补货面 51 类 **274 例**全绿 |
| D28-B1 | P2 | closed（F2） | 四个子页标题恒「仓库」、hint 恒「仓库 / 供应商 / 库存 / 采购与退货」（只有 transit 分支特判） | `WarehouseView.vue:8`、`:1278-1282` | 2026-10-29：`pageTitle` 由 `route.path` 反查 `NAV_ITEMS`（单一出处），四子页各传自己的 `hint` |
| D28-B2 | P2 | closed（F2） | 分组导航在子页里被 `props.only` 整个隐藏 ⇒ 从「库存与盘点」跳不到「仓间调拨」，只能回侧栏重找 | `WarehouseView.vue:318` | 2026-10-29：新增 `warehouse-sibling-nav`（同权限门过滤） |
| D28-B3 | P1 | closed（F4 + 2026-10-29 服务端导出补参） | 🔴「全部仓库」实为**单仓**：前端不传 `warehouseId` ⇒ 后端 `resolveWarehouseId(null)` 取默认仓；批次库存/流水/服务端导出同口径 | `WarehouseService:1581-1600`，调用点 `:146/:156/:209/:218`；`OpsCsvExportService:86-97` | 2026-10-29 走**可逆档**（只改 UI，不动 SQL 聚合）：`SINGLE_WAREHOUSE_TABS` 单一出处 `useWarehouseTabLoader.ts:24`；进这两个 tab 先 `pinSingleWarehouseFilter()`（`:120`）钉 ACTIVE 仓、请求显式带 `warehouseId`，筛选项禁清空＋占位「请选择仓库」（`useWarehouseListFilters.ts:72`、`WarehouseView.vue:151-157`）；导出前点破「服务端全量只认默认仓」，给「导本页（跟筛选）/仍导默认仓全量/取消」三选。**残留已闭合（2026-10-29）**：`GET /warehouse/export` 增可选 `warehouseId`/`status`（`OpsWarehouseController.java:282-283` → `OpsCsvExportService:91` 分派 `:158/:172/:187`），前端 `useWarehouseCsv.ts:296` `serverExportParams()` 只带筛选包里真实生效的键（inventory/movements⇒`warehouseId`，outbounds⇒`status`）；上面那个三选弹窗随之删除（服务端已能跟筛选，再问就是多余一问）。`openapi.ts` 走隔离栈 regen（临时 pg16/redis7 容器 + 本机新端口 jar，未碰 peer 的 :18080 与 `ai-cabinet-postgres-1`） |
| D28-B4 | P2 | closed（F5） | 6 个 tab 无前端权限门（只 `isPaneVisible`），`isWarehouseTabAllowed` 默认 `return true` ⇒ 点进去 403 | `WarehouseView.vue:353/:502/:521/:541/:555/:564`、`:1099-1107` | 2026-10-29：`isWarehouseTabAllowed` 照后端注解逐 tab 配对（**OR 码保留**：inventory/movements/monthly/outbounds/in-transit=OR(`warehouse:list`,`replenishment:list`)、transfers=OR(`warehouse:list`,`warehouse:edit`)、stocktakes/bins/channel-import=`warehouse:list`、采购族=`procurement:list`——别顺手收紧成单码，补货员/仓编辑会整组消失）；无权限 tab 连页签都不渲染，深链 `?tab=` 由 `applyTabFromQuery` 钳到 `firstAllowedTab()`；全 tab 隐藏时不再发必 403 的请求（`loadCurrentIfAllowed`）。机检 `.tmp/verify-b4-tab-perms.mjs` 8 断言全 PASS |
| D28-B5 | P2 | closed（2026-10-29） | 假筛选＋假总数：出库「待处理」客户端只过滤当前页（后端 `/outbounds` 无 status 参数）；在途「仅超时」同理，红条「共 N 条超时」只数当页 | `useWarehouseListFilters.ts:124`、`OpsWarehouseController.java:334-338` | 修法：筛选条件进 SQL。`WarehouseOutboundMapper.java:34` 5 参 `searchPage` 加 status（大写归一，空白＝不过滤，4 参重载原样委托 ⇒ `OpsCommercialFacade` 等调用点零改动）；`WarehouseInTransitMapper.java:76` 5 参 `searchPage` 加 `createdBefore`，`InTransitService.java:189` `TRANSIT_OVERDUE_HOURS = 24`（与前端 `useWarehouseListFilters.ts:33`、`OpsWorkbenchQueryService` 同源）；前端 `useWarehouseListFilters.ts:124` `reloadIfServerPaginated()` 改筛选即重载整页（原先只有内存过滤）。`actionable`（待处理＝DRAFT/PICKED 且有行）**刻意留在客户端**——它是复合条件、不是后端枚举值，传下去会变成第四种语义。铁律 75 红证：把两个 mapper 的 WHERE 临时挖掉 → `WarehouseOutboundTransitFilterSqlIT` 2/2 全红（total 期望 2 实得 3）→ 逐字节复原（sha256 比对）→ 2/2 绿。请求级实测 `.tmp/verify-b3b5-server-filters.mjs` 9 断言 PASS |
| D28-B6 | P2 | closed（F5） | `monthly` 列进 `SERVER_PAGINATED_TABS` 却又隐藏分页器，loader 根本不发 page/size ⇒ 总数取 `lines.length` | `WarehouseView.vue:1158-1172` vs `:585`、`useWarehouseTabLoader.ts:270-305` | 2026-10-29：`monthly` 移出 `SERVER_PAGINATED_TABS`（loader 本就整表返回、分页器隐藏），`tabTotal` 落回本地行数；后续若后端给月结上分页，把 tab 加回集合即可 |
| D28-B7 | P2 | closed（F5） | 加载失败只 toast，`finally` 仍标 hydrated ⇒ 页面显示「暂无库存」，把 5xx 伪装成「没数据」 | `useWarehouseTabLoader.ts` loadTab catch/finally | 2026-10-29：`failedTabs` 状态位——失败**不标** hydrated，表格上方出错误横幅（`data-testid="warehouse-tab-load-failed"`：这一页没加载成功≠没有数据）+「重试」按钮。Playwright 注入 500 实证 `.tmp/verify-b7-tab-load-failed.mjs` 9 断言（含重试后 200 恢复出数） |
| D28-B8 | P2 | closed（F5） | 导出按钮对 6 个 tab 静默空转（不在 `exporters` 表内，无提示） | `useWarehouseCsv.ts:263-272`（exporters 表）、`:334`（`exporters[tab]?.()` 兜底静默） | 2026-10-29：`WAREHOUSE_EXPORTABLE_TABS` 单一出处——无导出能力的 tab（stocktakes/bins/transfers/suggestions/monthly）**不渲染**导出按钮；Playwright 实测 `.tmp/verify-b8-b13-export-keyword.mjs`（5 tab 无按钮、inventory 有） |
| D28-B9 | P1 | closed（F5） | 权限码不配对：「批次报损」按钮门 `ops:warehouse:edit`，后端 `POST /inventory/write-off` 要 `ops:replenishment:edit`；索赔台账连 GET 都要 `replenishment:edit` | `WarehouseView.vue:841-845` vs `OpsReplenishmentController.java:230/245/254` | 拍板：**按后端码收紧前端**（放宽后端＝给 `warehouse:edit` 开报损权）。2026-10-29 落地：按钮门/索赔台账读写全改 `canReplenishEdit = ops:replenishment:edit`；Playwright 超管实测按钮在位（并入 B4 脚本断言） |
| D28-B10 | P2 | closed（F5） | 第 4 套分→元实现绕开门禁：`money()` 不带 ¥ ⇒ 躲过 `check:money-yuan-prefix`，拼进 `¥${money()}` 后负数渲染成 `¥-5.00`（规范 `yuanText` 出 `-¥5.00`）；`SupplierReconciliationView.vue:118-125` 又手写 7 处 | `useWarehouseLabels.ts:111-113`、`WarehouseView.vue:1395`、`display.ts:82-91` | 2026-10-29：①删掉 `money()`——金额格式化一律组件自己 `import { yuanText }`（经 props 注入的格式化器门禁同文件解析不到，正是绕开根源）；`money` prop 全部拆除。②门禁加第三条规则 `INJECTED_MONEY_PROP`：props 注入金额格式化器直接报错（自检 + 临时探针实证会红再删）。③未付余额列原先裸数字，补上 ¥；月结单价列、对账单 CSV 7 处手写换算收敛（CSV 要纯数字走 `centsToYuanDigits`）。Playwright 12 断言 PASS（`.tmp/verify-b10-money-cents.mjs`，含负额 `-¥5.00` / `-12.34` 双口径） |
| D28-B11 | P2 | closed（F4） | 库存流水页显示「未知」——**原单归类有误**：真正缺的是流动类型 3 个（`STOCKTAKE / BIN_STOCK / OUTBOUND_CANCEL`）＋关联业务类型 4 个（`STOCKTAKE / BIN_INBOUND / TRANSFER_OUT / TRANSFER_IN`）；`TRANSFER_*` 是 `ref_type` 不是 `movement_type` | `shared-dict/index.ts` 的 `warehouse_movement_type` + `business_reference_type` vs `WarehouseService:364/391/980`、`WarehouseTransferService:133/185`、`WarehouseBinService:148-160`、`ProcurementService:485-497` | 2026-10-29：src + `dist` 重建（`check:shared-dist-fresh`）＋ `SysDictBootstrap` 补种；**新增字典值不写迁移**——`SysDictBootstrap.run():76-80` 每次启动 `ensureMissingDictItems` 把缺的 (type,value) 补进已存在库。机检：`check:warehouse-movement-dict`（含负向 `-gate-negative`）钉住「后端在写的码 ⊆ 字典」＋两侧标签逐字相等 |
| D28-B12 | P2 | closed（F4） | `handover_status` 与 `in_transit_status` **都缺** `CANCELLED`（`InTransitService:134`、`WarehouseService:1017` 会写）⇒ 同样落「未知」 | `shared-dict/index.ts` 的两张 map | 同上（src+dist+Bootstrap，无迁移） |
| D28-B13 | P2 | closed（F5） | 筛选串味：`keyword`/`filterWarehouseId` 是跨 tab 共享 ref，关键词框只对 suppliers/purchase/returns 显示，但 `q` 仍被带进 inventory/movements 查询 | `useWarehouseTabLoader.ts:90-98` vs `WarehouseView.vue:175-179` | 2026-10-29：`warehouseListParams(withKeyword = false)`——只有 `loadPurchase` 传 `true` 透传 `q`，其余 tab 不再带隐形关键词；`filterWarehouseId` 属真实筛语义保留共享。请求级断言（`/purchase-orders?...q=DEMO`、outbounds 无 `q`）并入 B8 脚本 |
| D28-B14 | P3 | closed（F5，先实测后改） | 调拨收货弹窗 `:data="derived"` + `v-model="row.receivedQty"` 写进 computed 产物 ⇒ 损耗列疑不随实收刷新、提交 `lossQty` 恒 0 | `WarehouseTransferReceiveDialog.vue:110/117`、`:43/:82` | 2026-10-29 Playwright 实测**证实**：损耗列恒 0、提交 payload `receivedQty:97 + lossQty:0`（后端 `TransferLossValidator` 必 400 ⇒ 有损收货整个不可用，P3 定级偏低，实际是 P2）。修法：`:data` 改回响应式 `lines`、损耗逐行 `lossOf(row)` 推导；加「有损耗必选原因」前端闸（不再静默兜 `OTHER`）。实测 `.tmp/b14-measure-transfer-receive.mjs` 10 断言 PASS（0→3 刷新、payload `lossQty:3/lossReason:HANDLING_DAMAGE`） |
| D28-B15 | P1 | closed（F5，＝ module-audit 线索 #1 实证收口，超出原审计清单待追认） | V326 供应商对账单页是**死路**：路由 `warehouse/reconciliation` 在，但 NAV_ITEMS 没登记、meta 也没 perm ⇒ `findNavByPath` 解析不到，beforeEach fail-closed，**直链必 403（超管也是）**，页内无任何入口链过去；后端 `OpsProcurementController:96` 一直正常 | `config/menu.ts`（NAV_ITEMS 无此路径）、`router/index.ts:257` | 2026-10-29：补 NAV_ITEMS 登记（仓储组「供应商对账单」）。菜单码挂 `ops:warehouse:list`——🔴 实测教训：挂后端码 `ops:procurement:list` 会过 `hasPerm` 却卡 `isNavMenuActive`（ACTIVE 菜单表没有任何行用该码）仍然 403；故按兄弟子页口径挂 `warehouse:list`，「生成对账单」由页内 `canList`（=后端码）收口 + 「无采购查看权限」空态。`check:menu-permissions` / `check-nav-perms` 均绿，Playwright 实测页面可达 |

### 子 agent 归因留痕（判「错」未采信，勿重复上报）

`movementClass/deriveMovementClass/saveBinToChart` 全库 0 命中；「入库到货位仍恒 0」；「月结不含期初」「月结 tab 没有月份」；「后端 0 处权限校验」；「补货开门未接 trade 会话 / 90s CANCELLED」（已由 `36f7662d` 修）；「清理脏单无二次确认」；「商户端 0 处在途」；**「TransitTab 发运时间该用 `shippedAt`」——`warehouse_in_transit` 行就是在 `shipOutbound` 里 `setShippedAt(now)` 之后由 `recordFromOutbound` 创建的，`createdAt` 即该时刻，该条作废**；「备货两清单不一致」是设计留痕（事前规划 vs 事后反推互补，`replenishmentPrepList` 不排未签收是 CB-072 拍板）。

### 未跑实数据、不下结论的三项

① `purchaseCostCents` 单位（每支 vs 每件）——月结金额按它×支数，而 PO `unitCostCents` 明确「每件后折支」，两处录入口径不同则差异金额失真；② `purchase_order` 的 `CANCELLED` 只见字典不见写点；③ `resolveDefaultWarehouseId` 的「标准 ID 优先」在多仓时实际选哪个仓。

---

## 已确认「不是债」（勿重复开单）

| 点 | 说明 |
|----|------|
| 线长/商户打款按钮无 `v-hasPermi` | `canReviewWithdraw` / `canRetry*` 内已 `hasPerm` |
| 补货开门无单独 perm | 统一 `ops:replenishment:edit` + 确认框 |
| 订单退款/争议结案缺确认 | 已有 `ElMessageBox` + 权限 |
| 登录预填口令 | A-1 已禁；仅测试工具预填手机号 |
| RBAC fail-closed / Cookie 优先 | 已落地，见 lessons #41/#54/#55 |
| `useAutoRefresh` 设计 | 默认关、hidden 暂停、防滚雪球 |

---

## 修复日志（只追加）

### 2026-09-24 — D1
- 改动：`AdminEndpoints` 增 `disputesList`/`dispute`/`disputeClaim|Resolve|Close|Reopen`/`disputeSuggest`/`restockOpenDoor`；`DisputeListView`、`ReplenishmentView` 去裸路径；`ADMIN_ENDPOINT_PILOT_LITERALS` +`/ops/disputes`、`dispute-suggest`、`restock/open-door`
- 验证：`node scripts/check-admin-endpoints.mjs` → ok (66 pilot literals)；views/composables 对上述字面量 0 命中
- 回链：lessons #115；Changelog 同日 D1 行

### 2026-09-24 — D2
- 改动：`AdminEndpoints` 增 coupon*/promotion*/announcement*/feedback*；改 `CouponsView`/`PromotionsView`/`AnnouncementsView`/`FeedbackView`/`PointsRedeemView`/`UserAnalysisView`；门禁 +`/api/v2/coupons`、`/ops/promotions`、`/ops/announcements`、`/ops/feedback`
- 验证：`node scripts/check-admin-endpoints.mjs` → ok (70 pilot literals)；views 对四类字面量 0 命中
- 回链：lessons #115；Changelog 同日 D2 行

### 2026-09-24 — D3
- 改动：新增 `utils/soft-fallback.ts`（`softFallback` / `reportSoftFail` / `createSoftFailCollector`）+ 单测；仓配 `ensureMeta`/应付汇总/仓库筛选项、设备详情侧栏、大屏 `load`、打印元数据改为可见 warning（会话失效不 toast；大屏多路汇总一条）
- 验证：`npx vitest run src/utils/soft-fallback.test.ts` → 4 passed
- 回链：lessons #116；Changelog 同日 D3 行

### 2026-09-24 — D4
- 改动：新增 `composables/replenishment/useReplenishmentTaskActions.ts`；`ReplenishmentView` 柜门写路径（代签到/补货开门/完成上架/取消空路线）迁出；script ~1720→~1560
- 验证：`npx vue-tsc --noEmit`（admin-vue）exit 0
- 回链：lessons #117；Changelog 同日 D4 行

### 2026-09-24 — D5
- 改动：新增 `utils/money-ui-contracts.ts`（可退款状态、退款体、争议 resolve 体、提现审核门闩+体）+ `money-ui-contracts.test.ts`（8）；`OrderListView`/`DisputeListView`/`MerchantWithdrawView` 写路径复用，禁止再手写一套
- 验证：`npx vitest run src/utils/money-ui-contracts.test.ts` → 8 passed
- 回链：lessons #118；Changelog 同日 D5 行

### 2026-09-24 — D6
- 改动：新增 `utils/device-lifecycle-guards.ts` + 5 测；`composables/device/useDeviceLifecycleActions.ts`（绑定/投放态机/解绑硬件/重生编号）；`DeviceDetailView` 接线；Warehouse 侧确认已有 bins/transfers/… composable + Dialogs，本轮不硬拆巨型模板
- 验证：`npx vitest run src/utils/device-lifecycle-guards.test.ts` → 5 passed；`npx vue-tsc --noEmit` exit 0；DeviceDetailView ~2750→~2580 行
- 回链：lessons #119；Changelog 同日 D6 行

### 2026-09-24 — D7
- 改动：新增 `utils/admin-catalog-query.ts`（`ADMIN_CATALOG_PAGE_SIZE=500` / `ADMIN_OPTIONS_PAGE_SIZE=200` + query 助手）+ 3 测；`AdminEndpoints` 目录/选项字面量改走助手，并增 `merchantsCatalog`/`devicesOptions`；DeviceList/Operator/MerchantSplits/Lifecycle 去散落 `page=0&size=*`
- 验证：`npx vitest run src/utils/admin-catalog-query.test.ts` → 3 passed；`vue-tsc --noEmit` exit 0；views 对 `page=0&size=500|200` 0 命中
- 回链：lessons #120；Changelog 同日 D7 行

### 2026-09-24 — D8
- 改动：`FeedbackView` 行类型改为 `UserFeedbackDto`；`RiskView` 改为 `OpenApiRiskEventDto` / `OpenApiUserBlacklistDto`（`shared-types` 增导出）；仓配/补货多 Tab 动态行仍 `Record<string, any>`（与 D4 注释一致，另开）
- 验证：`npx vue-tsc --noEmit`（admin-vue）exit 0
- 回链：lessons #121；Changelog 同日 D8 行

### 2026-09-24 — D9
- 改动：`auth.ts` 2FA challenge 改存 `sessionStorage`；读写助手顺带清 localStorage 遗留；`logout` 同步 `clearTwoFactorChallenge`
- 验证：目视 diff + `vue-tsc`（与 D8 同轮）exit 0；中间态关闭标签即失效，符合预期
- 回链：lessons #122；Changelog 同日 D9 行

### 2026-09-24 — D10
- 改动：停写 `admin_permissions` / `admin_active_nav`（本就不读，易误导+XSS 抬权面）；成功/失败/logout 只 `clearLegacyRbacCache`；RBAC 仅内存 + `/me` 拉取
- 验证：`auth.ts` 无 `setItem(PERM_KEY|NAV_KEY)`；`vue-tsc --noEmit` exit 0
- 回链：lessons #123；Changelog 同日 D10 行

### 2026-09-24 — D11
- 改动：从 `AdminEndpoints` 删除无前端调用方的 `dataTables` / `dataSchema` / `dataRow` / `dataCreate` / `dataUpdate`；保留 `dataCapabilities` + `dataDelete`（CrudTable 在用）
- 验证：全仓 `AdminEndpoints.data(Tables|Schema|Row|Create|Update)` 0 命中；`vue-tsc` exit 0
- 回链：lessons #124；Changelog 同日 D11 行

### 2026-09-24 — D12
- 改动：`endpoints.ts` 文件头补前缀约定表（ops/admin、disputes/restock、coupons、promotions/announcements/feedback、auth、public）+ 门禁脚本指针
- 验证：文档审查；与 D1/D2 门禁扩表一致
- 回链：lessons #125；Changelog 同日 D12 行

**本轮 D1–D12 清单已清完。** 首刀遗留已开单为 **D13–D15**（见上「二期后续」），不再用口头「另开」代替进度表。

### 2026-09-24 — 开单 D13–D15（仅文档）
- 改动：将 D4/D6/D8 完成记录中的后续拆分正式登记为 D13（补货再拆）、D14（仓配模板/设备详情续拆）、D15（仓配·补货去 `any`）；建议顺序 D15→D13→D14
- 验证：本表状态列可见三行 `open`；无代码变更
- 回链：Changelog 同日「D13–D15 开单」行；G4 指向二期

### 2026-09-24 — D15
- 改动：新增 `types/admin-dynamic-row.ts`（集中替代散落 `Record<string, any>`）；仓配全部 `Warehouse*Row` + Warehouse/Replenishment View 的 `Row` 改用；`useReplenishmentTaskActions` 柜门写路径改 `OpenApiReplenishmentTaskDto`/`OpenApiReplenishmentRouteDto`/`ReplenishmentDeviceRef`；`shared-types` 增 Route/Warehouse 等 OpenAPI 别名；并恢复 `useWarehouseTabLoader` softFallback（误 checkout 回退）
- 验证：`npx vue-tsc --noEmit` exit 0；仓配/补货路径无 `= Record<string, any>`（仅 `AdminDynamicRow` 定义处）；`vitest soft-fallback` 4 passed
- 回链：lessons #126；Changelog 同日 D15 行

### 2026-09-24 — D13
- 改动：新增 `useReplenishmentRequestFlow`（要货审批流/接驳回/附图）+ `useReplenishmentTaskLines`（理货明细抽屉/货道分配/现场证/待分配 hint）；`ReplenishmentView` 接线，模板 `goRequestTask`→`onRequestAction(..., 'view-task')`
- 验证：`npx vue-tsc --noEmit` exit 0；View 行数 ~3016→~2764（composable 约 295+314）
- 回链：lessons #127；Changelog 同日 D13 行

### 2026-09-24 — D14
- 改动：新增 `composables/device/useDeviceTempEnv.ts`（温控计划 / 环境读数 / SET_TEMP）；`DeviceDetailView` 接线；货道编辑与 Warehouse 巨型模板本轮不硬拆
- 验证：`npx vue-tsc --noEmit` exit 0；DeviceDetail ~2662→~2570
- 回链：lessons #128；Changelog 同日 D14 行

### 2026-09-24 — 开单并完成 D16
- 改动：三期开单 D16；新增 `useDeviceSlotActions`（套模板/刷新/编辑弹窗/盘点/保存，保留实盘一并提交纪律）；`DeviceDetailView` 接线；Warehouse 模板 pane 仍不硬拆
- 验证：`npx vue-tsc --noEmit` exit 0；DeviceDetail ~2570→~2413
- 回链：lessons #129；Changelog 同日 D16 行

### 2026-09-24 — 开单并完成 D17
- 改动：四期开单 D17；新增 `WarehouseSuggestionsTab`（采购建议）+ `WarehousePayablesTab`（应付账款）；`WarehouseView` 接线；采购单/出库单等大 pane 本轮不硬拆
- 验证：`npx vue-tsc --noEmit` exit 0；WarehouseView ~3242→~2929
- 回链：lessons #130；Changelog 同日 D17 行

### 2026-09-24 — 开单 D18（仅文档）
- 改动：将 D17 遗留的采购单/出库单 pane 正式登记为 D18；建议顺序 采购单→出库单
- 验证：本表「五期后续」可见 D18 `open`；无代码变更
- 回链：Changelog 同日「D18 开单」行；G4 指向五期

### 2026-09-24 — D18
- 改动：新增 `WarehousePurchaseOrdersTab`（采购单 expand/审批/收货/打印）+ `WarehouseOutboundsTab`（出库单 expand/拣发运/作废，保留 data-testid）；`WarehouseView` 接线；写动作仍在父级 composable
- 验证：`npx vue-tsc --noEmit` exit 0；WarehouseView ~2929→~2550
- 回链：lessons #131；Changelog 同日 D18 行

### 2026-09-24 — 开单并完成 D19
- 改动：六期开单 D19；新增 `WarehouseTransfersTab` / `WarehousePurchaseReturnsTab` / `WarehouseStocktakesTab` / `WarehouseBinsTab`；`WarehouseView` 接线
- 验证：`npx vue-tsc --noEmit` exit 0；WarehouseView ~2550→~2070
- 回链：lessons #132；Changelog 同日 D19 行

### 2026-09-24 — 开单并完成 D20
- 改动：七期开单 D20；新增 `WarehouseOverviewTab` / `WarehouseSuppliersTab` / `WarehouseTransitTab` / `WarehouseInventoryTab` / `WarehouseMovementsTab`；`WarehouseView` 接线；清理 View 多余 dict/format/TableActions 导入
- 验证：`npx vue-tsc --noEmit` exit 0；WarehouseView ~2070→~1665
- 回链：lessons #133；Changelog 同日 D20 行

### 2026-09-24 — 开单并完成 D21
- 改动：八期开单 D21；新增 `useDeviceAsset`（资产 GET/PATCH/geo）+ `DeviceAssetDeploymentCard`（表单+生命周期按钮条 UI）；`DeviceDetailView` 接线；生命周期写路径仍走既有 composable
- 验证：`npx vue-tsc --noEmit` exit 0；DeviceDetailView ~2412→~2004
- 回链：lessons #134；Changelog 同日 D21 行

### 2026-09-24 — 开单并完成 D22
- 改动：九期开单 D22；新增 `useDeviceRelatedRecords`（会话/订单 softFallback + total）+ `DeviceRelatedRecordsTab`；`DeviceDetailView` 接线；跳转仍 emit→`goPath`
- 验证：`npx vue-tsc --noEmit` exit 0；DeviceDetailView ~2004→~1767
- 回链：lessons #135；Changelog 同日 D22 行

### 2026-09-24 — 开单并完成 D23
- 改动：十期开单 D23；新增 `useDeviceRemoteOps`（指令/退款/策略锁/维修）+ `DeviceRemoteOpsCard`；`DeviceDetailView` 接线；`cmdLoading` 仍与温控共用
- 验证：`npx vue-tsc --noEmit` exit 0；DeviceDetailView ~1767→~1351
- 回链：lessons #136；Changelog 同日 D23 行

### 2026-09-24 — 开单并完成 D24
- 改动：十一期开单 D24；新增 `DeviceTempEnvTab`（温控计划+环境监控 UI）；逻辑仍 `useDeviceTempEnv`；首屏设温留 hero
- 验证：`npx vue-tsc --noEmit` exit 0；DeviceDetailView ~1351→~1255
- 回链：lessons #137；Changelog 同日 D24 行
- **事故**：同日补货规划切片误 `git checkout` 回退 `ReplenishmentView` 未提交的 D13 接线 → 见 D25

### 2026-09-25 — 开单并完成 D25
- 改动：恢复 `ReplenishmentView` 对 `useReplenishmentTaskActions`/`RequestFlow`/`TaskLines` 接线；再接 `useReplenishmentRoutePlanning` + `ReplenishmentPlanRouteDialog`；清理无用 import
- 验证：`npx vue-tsc --noEmit` exit 0；View ~3378→~2467
- 回链：lessons #138；Changelog 同日 D25 行

（D1–D25 追踪表内 open 项已清。）

### 2026-10-28 — 开单 D26（仅盘点）
- 改动：无代码。用户报障「理货记录还有货道的概念」后全量 `grep 货道 clients/admin-vue/src`，把 84 处 / 20 文件按 A（误导）/B（货道柜固有）/C（注释·关键词）分类写进「十三期」节
- 验证：计数由 `grep -rn 货道 --include=*.vue --include=*.ts` 现取（不含 `static/` 构建产物）；A 类每条带 `file:line` 与「为什么误导」
- 回链：判据见 `PROJECT_KNOWLEDGE` 2026-10-28「开门柜=jiangyi BOUND」行

### 2026-10-28 — 三页缺陷整改（用户报障，未预先开单）
- **月结单台账**（`views/warehouse/WarehouseMonthlyCloseSheetsView.vue`）：生成区+筛选并入 `filter-bar filter-bar--compact`，裸 `el-table` 换 `CrudTable`+`useCrudTable`（去掉写死的 `page=0&size=50`，第 51 单往后此前看不到）；状态/期初来源/处置三处中文标签迁 `@aicabinet/shared-dict`（新增 `close_sheet_status` / `close_sheet_opening_source` / `close_sheet_disposition`）；`¥${(v/100).toFixed(2)}` 自建 `fmtCents` 换 `yuanTextZero`；抽屉行表补 `col-*` 语义 class。详情抽屉的行表**仍是裸 `el-table`**（无独立分页需求），故本页合法留在 `admin-table-gate-baseline.json` 内
- **仓库近效期预警**（`views/warehouse/WarehouseExpiryAlertsView.vue`）：仓库筛选由自由文本 `el-input`（要手敲仓库 id）改 `el-select`（名称显示 / id 提交，取 `warehouseListAll`），仓库列做 id→名称换算；查不到时退回原 id 而非显示「暂无」。🔴 仓库**不是** sys_dict：字典里只有 `warehouse_status` 等枚举，主数据在 `warehouse` 表
- **理货记录**（`views/growth/ReplenishmentStaffView.vue` + 后端）：删「货道」列；顺带修撞 key——`TallyRecordRowDto` 补 `lineId`（SQL **投影列**增 `l.line_id`），前端 row-key 从 `taskId:skuId:slotId` 改 `lineId`（开门柜 `slot_id` 恒 null ⇒ 同任务同 SKU 两行会撞，Vue 复用节点后勾选/展开错行）；`packages/shared-types/src/generated/openapi.ts` 只补 lineId 两行
- 验证：`npx vue-tsc --noEmit` exit 0；`check-admin-table-gate` OK（裸表格 17，基线未放宽）；`check-admin-table-align` ok（bare center 0，新增 WARN 0）；`mvn -o -pl services/trade-service -am -Dtest=TallyLedgerServiceTest -Dsurefire.failIfNoSpecifiedTests=false test` → surefire `Tests run: 2`（`<testcase>` 计数）exit 0；Playwright 三页实测 **27/27**（`vite` 3123 + `page.route` 只读 mock，跑法 `.tmp/verify-1028-three-pages.mjs`）
- 🔴 负向对照（两条判据被证伪后订正）：
  1. **DOM 层证不了撞 key**：把 mock 的 `lineId` 抹平重跑，「无 Duplicate keys 告警」仍然绿——本表既无勾选列也无展开列，`el-table` 只在 patch 乱序时才对重复 key 告警（`render-helper` 的 `getKey` 走 `getRowIdentity`，但 `patchKeyedChildren` 的前缀同步会把同序子节点全吃掉）。所以这条只是 canary，真判据挪到静态差分 `.tmp/static-control-1028.mjs`：同一份断言跑 `git show HEAD:` ⇒ **4 条红**，跑工作树 ⇒ 全绿。
  2. **`contains("l.line_id")` 是弱断言**：HEAD 的 SQL 里 `ORDER BY … l.line_id ASC` 也含这个字面，单测在修复前照样绿。改成断言 `SELECT l.line_id`（投影位置），差分实测 HEAD 红 / 工作树绿。
- 「货道」列删除的 DOM 判据是真能红的：临时把列加回去（备份 sha256 `1f35ce09…`，跑完逐字节还原并 `sha256sum -c` OK）⇒ 该检查变红且**只有它**变红（26/27，表头读出 `… 数量 货道 已应用 任务`）
- 未做（本轮范围外，**已另修**见下条）：merchant-mp 的 `pages/tally-records/tally-records.vue:220` `:233` 同样有「货道」展示与 `taskId-skuName-slotId` 撞 key——用户只点了运营后台，当时只留痕
- **2026-10-28 追修 merchant-mp 同缺陷（用户追加拍板）**：只改行身份，货道文案**保留**——商户端那处是 `r.slotId ? \`货道 ${r.slotId} · \` : ''`，开门柜 slot_id 恒 null 时一个字都不显示，和运营后台「永远空着的一整列」不是一回事；商户名下可能真有货道柜，删了丢信息。改动三处：`RestockRow` 加 `lineId`、`toRestockRows` 映射 `Number(r.lineId ?? 0)`、`:key` 由 `${taskId}-${skuName}-${slotId}` 改 `r.lineId`（这页有「加载更多」增量渲染，撞 key 就是错行）。后端无需改（`lineId` 已在上一笔的 DTO/API 里）
  - 验证：`pnpm --filter @aicabinet/merchant-mp type-check` exit 0；`test` 32 文件/211 例通过；`build:mp-weixin`（release）exit 0，且**读产物判据**：`dist/build/mp-weixin/pages/tally-records/tally-records.js` 里 `j:a.lineId`、`tally-records.wxml` 的 restock 列表 `wx:key="j"`（盘点列表仍是 `wx:key="c"`=eventId），旧拼法字面 0 次

### 2026-10-28 — 上述整改的**落笔实况**（归因留痕，用户拍板「不改历史」）
- 这 11 个文件**没有独立提交**：`git add` 之后我在跑预检，peer 于 20:45:56 在**同一棵主树**执行 `git commit`，把已 staged 的 11 个文件一并吞进他们的 `144a07dc feat(stocktake): 盘点单号改为可读 PDyyyyMMdd-NNN`。🔴 根因是**共享索引**：主树的 index 只有一个，谁 commit 都会带走别人 staged 的内容。
- `144a07dc` 内文件归因（两组**不相交**，故内容没互相污染）：
  - 本轮三页整改 11 个：3 个 view（月结台账 / 近效期 / 补货员效率）+ `packages/shared-dict/{src/index.ts,dist/index.js,dist/index.d.ts}` + `packages/shared-types/src/generated/openapi.ts` + `TallyRecordRowDto.java` + `TallyLedgerService.java` + `TallyLedgerServiceTest.java` + 本表
  - peer 的盘点单号 7 个：`warehouse-stocktake.vue`、`biz-no.test.ts`、`packages/shared-uni/src/format.ts`、`WarehouseStocktakeMapper.java`、`WarehouseStocktakeService.java`、`WarehouseStocktakeNoTest.java`、`WarehouseStocktakeServiceTest.java`
  - 核对法：`git show HEAD:<f> | diff --strip-trailing-cr - <f>` 逐文件比，11 个全同（`services/**`、`docs/**` 没设 `eol=lf`，不去 CR 会假报整文件差异）
- dist 未被 peer 脏源码烤进去：peer 在 `shared-dict/src/index.ts` 里另有一处未提交改动（`inventory_change_type` 加 `DEMO_SEED/OPENING_BACKFILL/WRITE_OFF/GUEST_WATER`），我先摘掉那 4 行 → 重建 dist → `git add` → 逐字节还原（src `98b91d9b…` OK，dist 重建后回到 `4737c5e6…`/`5e238bd6…`）。用 detached worktree 按 `check-shared-dist-fresh` 的等价逻辑复算**提交树**：committed dist == HEAD src 重新编译（37163B / 28424B 逐字节一致）⇒ 这笔自己不会把门禁搞红。
- 🔴 三条后续纪律（本轮踩过）：
  1. 共享主树里 `git add` 与 `git commit` 之间**不许插长跑命令**；或直接用 `git commit -F <msg> -- <路径>`（`--only` 模式不依赖共享索引，谁在这窗口 commit 也带不走我的文件）。
  2. `reset --soft HEAD~1` 前必须先 `git log -1` 确认 tip 还是自己要动那笔。peer 在我 staging 后又压了 `bf85febe build(admin)`，我那次 `HEAD~1` 撤的是**他们的产物提交**（已 `git reset bf85febe` 原样恢复，`static/admin` 差异 0 处）。
  3. 重写历史要先数清「压在上面的有几笔」：一旦上面还压着 peer 的提交，拆笔就从「重写 1 笔」变「重写 N 笔」，代价跳档——本轮据此改选「不改历史 + 本条留痕」。
- 旁证：peer 的 `bf85febe` 已提交产物里含 `月结单台账（审批锁账` 与 `时间窗内无理货记录` 两处字面量 ⇒ 那次重建是从**已含本轮源码**的树跑的；未见 `restock-detail` 字面量。

### 2026-10-29 — D26 A 类 8 处落地（用户拍板「A 类全部 8 处一起改」）
- **删列 4**：`components/warehouse/WarehouseOutboundsTab.vue`（出库明细「货道」，恒 `slotId || '无'`）；`views/print/PrintView.vue`（拣货单 `<th>货道</th>` + `<td>{{ line.slotId || '待分配' }}</td>` + 脚注「…核对批次与货道」→「…核对批次」）；`views/orders/OrderListView.vue`（主表「货道」列 + 明细 `prop="slotId"`，同时删 `goodsDisplay()` 里**只服务该列**的 `slots: lines.map((g) => g.slot)`；`parseGoodsLines`/`goodsDisplay` 仍服务商品列，保留）；`views/disputes/DisputeListView.vue`（明细 `prop="slotId"`）
- **改词 4**：`components/device/DeviceJiangyiCard.vue`（`device_sku_inventory 走既有「货道管理」`→「由补货/理货流程维护」，hint 的「库存走『货道陈列』」同改）；`views/growth/SkuReviewView.vue`（5 处「不改货道」→「不改柜内在售商品」/ 占位符「不改在售商品」）；`views/vision/LearningLibraryView.vue`（「货道在售 ≠ 识别上架」→「柜内在售 ≠ 识别上架」×2）；`views/analytics/FootfallView.vue`（标题/空态补「仅货道柜」与「开门柜订单不含货道坐标」）
- 判据来源：用户 2026-10-28 拍板「这些界面服务的柜机固定都是将邑开门柜」⇒ 不需要行级柜型投影，直接删列
- 验证：`npx vue-tsc --noEmit` exit 0；`check-admin-table-gate` OK（裸表格 17，基线未动）；`check-admin-table-align` ok（bare center 0，新增 WARN 0，13 条 money WARN 全在未触碰文件）；`prettier --check` 8 文件全绿（`FootfallView` 首次红＝我把 `<p>` 折行，`--write` 后 `git diff` 只有 3 行文案，无夹带）；`eslint` 0 error（2 条 `no-unused-vars` WARN 在 `DisputeListView:682/:694`，本笔未触及）
- 🔴 计数口径：`grep -o 货道` 从 **96 处 / 21 文件**（HEAD `8c74cfd2`）→ **86 处 / 17 文件**。删列的 4 个文件各留 1 行 `<!-- 无「货道」列：… -->`，`FootfallView` 因加限定词反而 +3，所以**不能拿总数下降幅度当成绩单**，要按文件核
- 未动：`ReplenishmentView` 8 处（等缺货新口径）、B 类（设备详情货道编辑 / `useDeviceSlotActions` / `SlotInlineRow` / `order-line-summary` + 单测 / `ConsistencyView:401` / planogram 开关）、C 类（注释 / 菜单搜索词 / 端点说明）
- `FootfallView` 的柜机下拉仍列全部柜机（含开门柜）：**决定不做柜型过滤**——用户 2026-10-29「待办 我们上线后只有一种类型」⇒ 恒空列直接删列/改词，不引入「按柜型条件显示」分支，也就不需要 `jiangyi BOUND` 的前端投影；选到开门柜时模块空态是预期结果，标题已写明「仅货道柜」

### 2026-10-29 — D28 F4 落地（B3 仓库口径 + B11/B12 字典补全）
- **B3（可逆档，只改 UI）**：`SINGLE_WAREHOUSE_TABS = {inventory, movements, monthly}` 作单一出处；`inventory`/`movements` 的 loader 改成 `loadWarehousesSoft().then(loadX())`（原 `Promise.all` 会与仓库列表赛跑 ⇒ pin 拿不到仓）、请求前 `pinSingleWarehouseFilter()` 钉第一个 ACTIVE 仓；筛选项 `:clearable="!warehouseFilterRequired"` ＋ 占位「请选择仓库」；导出前 `ElMessageBox.confirm` 点破「服务端全量只认默认仓」，三选（导本页/仍导默认仓全量/关闭）。
- **B11/B12 字典**：`warehouse_movement_type` 补 3 码、`business_reference_type` 补 4 码、`handover_status`/`in_transit_status` 各补 `CANCELLED`；src + `dist` 重建 + `SysDictBootstrap` 补种。**不写迁移**：`SysDictBootstrap.run():76-80` 每次启动对整张目录 `ensureMissingDictItems`，已存在库同样补齐（V415 那种「改已有标签」才必须带旧标签锚点）。
- **新门禁** `check:warehouse-movement-dict`：静态抠出后端真正写进 `warehouse_movement.movement_type/ref_type`、`warehouse_outbound(_line).handover_status`、`warehouse_in_transit.status` 的码（构造实参 + `setXxx(...)` 两路，动态实参必须登记），断言「写 ⊂ 字典」＋ shared-dict ↔ SysDictBootstrap 值→标签逐字相等；负向 `verify-warehouse-movement-dict-gate.mjs` 11 条注入逐条挣红并还原绿、真实树 md5 前后一致。
- **UI 验收**（Playwright，`/tmp` 脚本 `.tmp/verify-b3-warehouse-export.mjs`，17 条全 PASS）：两个 tab 的列表请求带 `warehouseId=WH-DEMO-001`、筛选项显示「演示中心仓」且无 clear 图标；导出弹窗出现且两条分支都走对（本页=客户端下载且零服务端导出请求；默认仓全量=`?tab=inventory|movements`）；`outbounds` 不弹窗、照旧服务端导出；零页面 JS 异常。`vue-tsc --noEmit` exit 0。
- 已知残留（均已归档，不在本轮修）：① 服务端导出不接 `warehouseId`/`q` ⇒ 并入 openapi regen 批次；② 首帧若 `loadTab` 被并发调两次，先到的那次 pin 时仓库列表还没落地，会多发一次**不带 warehouseId** 的读——响应被 `loadSeq('loadInventory')` 判旧丢弃，用户看不到，不属缺陷。
- 计数订正（原单写错，已改行内）：B11 不是「5 个流动类型全缺」，而是流动 3 + 关联 4，且 `TRANSFER_*` 属 `ref_type`；B12 是**两张状态字典都缺** `CANCELLED`。

### 2026-10-29 — D28 B3 残留 + B5 收口（服务端筛选/导出补参，隔离栈 regen）
- **后端（三个可选参数，全部走重载，调用点零改动）**：`WarehouseOutboundMapper.searchPage(keyword, warehouseId, status, page, size)`（status 大写归一、空白＝不过滤）、`findAllFiltered(warehouseId, status)`（服务端全量导出用）、`WarehouseInTransitMapper.searchPage(deviceId, status, createdBefore, page, size)`。`InTransitService.listInTransitPage` 把 `overdueOnly` 翻成 `now − TRANSIT_OVERDUE_HOURS(24h)`；`OpsWarehouseAdminService` 各加一层同名重载；Controller：`/outbounds` 加 `status`、`/in-transit` 加 `overdueOnly`、`/export` 加 `warehouseId`+`status`。
- **口径同源**：超时 24h 三处字面量（前端 `useWarehouseListFilters.ts:33`、`OpsWorkbenchQueryService`、`InTransitService:189`）本批次只加注释钉住，**没有**抽常量跨模块共享——跨模块抽常量要动 shared-types，留给后续；先保证数字一致、注释互相指认。
- **前端**：`useWarehouseCsv.ts` 删掉三选弹窗与 `SINGLE_WAREHOUSE_TABS` 依赖，`serverExportParams()` 只带本 tab 真实生效的筛选键；`useWarehouseListFilters.ts` `reloadIfServerPaginated()` —— 服务端分页的 tab 改筛选就整页重载（客户端分页 tab 保持原行为）。
- **openapi regen 全程隔离**：临时 `postgres:16-alpine` + `redis:7-alpine`（`--rm`）＋ 本机新端口 **:28085** 跑新 jar，从空库 Flyway 迁移后抓 live spec（698 paths）；**未碰** peer 的 :18080 共享栈与 `ai-cabinet-postgres-1`(:15433)。diff 逐行核过：本批次 5 个参数 + 3 个 DTO 字段（`remainderQty`/`shelvedQty` 等来自本会话 F1 月结未提交改动，非他人代提交）。`packages/shared-types` dist 重建，admin-vue `type-check` 绿。
- **铁律 75 红证**：把两个 mapper 的 WHERE 临时挖掉 → 新 IT `WarehouseOutboundTransitFilterSqlIT` **2/2 全红**（`total` 期望 2 实得 3，报错文案正是「WHERE 没进 SQL」）→ 从 `.tmp/*.bak` 逐字节复原并 sha256 与记录值比对一致 → 2/2 绿（新 failsafe 报告 17:36:27）。
- **请求级实测** `.tmp/verify-b3b5-server-filters.mjs` 9 断言 PASS：出库 `status=DRAFT` 真下发、在途 `overdueOnly=true`、导出带 `warehouseId`/`status`、`actionable` 仍留客户端、非单仓 tab 行为不变。
- 门禁：`check:openapi-regen` / `check:openapi-types` / `check:shared-dist-fresh` / `check:admin-endpoints` / `check:admin-endpoint-paths` / `check:admin-table-align` / `check:admin-page-size` / `check:csv-escape` / `check:inmemory-paging` / `check:money-yuan-prefix` / `check:mybatis-null-clear` / `check:competitor-benchmark` / `gate:admin-tables` 全绿；5 个改动前端文件 `prettier --check` 全绿、`eslint` 0 error（1 条 `receiveTransfer` 未使用 WARN 在 `WarehouseView.vue:1777`，不在本笔 diff 内）。
- **已提交并推送**（2026-10-30）：`aa5004b0` 代码 / `eeeb4fb7` 文档 / `21a48e86` `static/admin` 保真产物，dev tip `21a48e86`。peer 脏文件（`static/admin` 的 Windows 工作区构建、`docs/PASSWORD_FREE_ONBOARDING_EMAILS_2026-10-26.md`、`docs/figures/*.txt`、`.idea/*`、`docs/JIANGYI_INTERFACE_CHECKLIST.md`、`docs/module-audit/`）一律不代提交；admin 产物改由 `build-admin-ci-fidelity.sh` **从已提交的 HEAD** 干净检出重建，故不会把工作区脏源码烤进产物（判据详见 `docs/PROJECT_KNOWLEDGE.md` §12 2026-10-30 条目）。

### 2026-10-30 — D28 A4 收口（拍板「先诚实化」，A1–A9 本轮全部闭合）
- **范围**：只让注释与页面文案不再撒谎，**6 个总账写入点一个没动**。「全链路写 bin（＋对账）」作为另一档**仍未拍板**——真要接，`WarehouseBinServiceTest.stockWritingServicesHoldNoBinLedgerDependency` 会先红，红信息点名要同步删掉本类的边界注释与 bins 页文案，防止「代码接了、文档还说没接」。
- **证据链**：类注释三条边界见 `WarehouseBinService:27-37`；bins 页文案 `WarehouseView.vue:1084`；旧文案「出入库单据会引用」的证伪=`grep bin_id` 全仓只命中 V161/V237（建表/索引，无单据外键）。
- **测试**：`WarehouseBinServiceTest` 7→9 例。铁律 75：注入反向改动 ⇒ `Tests run: 9, Failures: 2` ⇒ 逐字节复原（工作树 sha256 回到 `25e1063efd4a…8dc7a1b70`）。
- 🔴 **绿证在隔离 worktree 取的**：共享工作树被 peer 的 CB-076 在途重构（未跟踪 `SkuStockLevel.java`）弄到编译不过 ⇒ 主树红/绿均不可归因。`git worktree add --detach .tmp/a4-green 0559f662` + 只拷本批 4 文件 ⇒ `WarehouseBinServiceTest` 9/0 ＋ `DeviceSlotServiceTest` 13/0 ＋ BUILD SUCCESS（报告 mtime 09:11）。
- 🐛 **顺带闭合 CI `build` 红**：`21a48e86`/`0559f662` 两次红的唯一用例是既有 `DeviceSlotServiceTest.getDeviceDetail_fallsBackToBookQtyOnlyForSkuNeverLedged`——D28-F3 我在 `computeMetrics` 加的门控读 `jiangyiDeviceDirectory`，该夹具把它传了 `null` ⇒ `isDoorCabinet:882` NPE。已换成默认 false 的显式桩；同一块隔离 worktree 里复刻 CI 命令 `mvn verify -DskipITs -pl services/trade-service -am` ⇒ BUILD SUCCESS、439 份 surefire 报告、`<testcase>` **2540 / 0 失败 / 0 错误 / 0 跳过**。教训 #363（判据入口改动必须跑 CI 同一条全量，不能只跑按业务名字挑的子集）。
- **预检**：首轮 `pre-push-ci-preflight.mjs` 在 `format:check` 中止，红的两个文件（`MerchantSplitsView.vue`、mp `device-detail.vue`）经归因**均属 peer 在途改动**（`git status` 显 `M`；HEAD 版 `prettier --stdin-filepath` exit 0、工作树版 exit 1）⇒ 未代改；peer 自行格式化后终轮预检 exit 0（含两端 mp type-check、eslint 0 error、审计门禁链、4 道 migration 门禁、openapi-regen、admin-bundle-budget、openapi-types）。
- 🔴 **行级归因**：拷完文件后 peer 又往**同一个** `DeviceSlotServiceTest.java` 追加两处 hunk（该文件从我的 +4/−1 变 +55/−8）。按文件 `git add` 会把 peer 在途重构烤进本笔 ⇒ 用 `git apply --cached` 只提交我自己的 hunk，提交后复查 peer 的行仍原样留在工作树（48+/7−）。教训 #364。
- **产物**：`build-admin-ci-fidelity.sh` 从已提交 HEAD `3466e724` 干净检出重建，产物一笔 `bff0bbe3`（113 文件）。判据：新文案「仓内位置的独立台账」在 `WarehouseView-DdEuNA2a.js` 命中 / 旧文案 0 命中 / `127.0.0.1:18080` 0 命中 / bundle 预算 OK（133.4KB≤150、124.6KB≤130、总 JS 3405.6KB≤3500）。
- **peer 脏文件**（一律未代提交、未改动）：CB-076 全套（`DeviceSlotService.java`/`DeviceAssetService.java`/`MerchantInventoryPortalService.java`/`support/SkuStockLevel.java`/`DeviceSkuInventoryMapper(.java|.xml)`/`ReplenishmentService.java`/`WarehouseService.java`/`MerchantShortageSummaryDto.java`/admin 两页/mp 两页/`DeviceSlotServiceTest.java` 的两处 hunk）、`.idea/*`、`docs/COMPETITOR_BENCHMARK.md`、`docs/JIANGYI_INTERFACE_CHECKLIST.md`、`docs/PASSWORD_FREE_ONBOARDING_EMAILS_2026-10-26.md`、`docs/figures/*.txt`、`docs/module-audit/`。

---

## 关联

- 活文档：`docs/PROJECT_KNOWLEDGE.md` §9 / §10（G4）
- 踩坑总册：`docs/engineering/lessons-learned.md` #115+
- 端点目录：`clients/admin-vue/src/api/endpoints.ts`
- 门禁：`scripts/check-admin-endpoints.mjs`
- 二期：本表 **D13–D15**（「二期后续」节）
- 三期：本表 **D16**（「三期后续」节）
- 四期：本表 **D17**（「四期后续」节）
- 五期：本表 **D18**（「五期后续」节）
- 六期：本表 **D19**（「六期后续」节）
- 七期：本表 **D20**（「七期后续」节）
- 八期：本表 **D21**（「八期后续」节）
- 九期：本表 **D22**（「九期后续」节）
- 十期：本表 **D23**（「十期后续」节）
- 十一期：本表 **D24**（「十一期后续」节）
- 十二期：本表 **D25**（「十二期后续」节）
- 十三期：本表 **D26**（「十三期」节 · 开门柜货道残留，2026-10-28 盘点 → 2026-10-29 A 类 8 处改码，仅剩 `ReplenishmentView`）
- 十四期：本表 **D27**（「十四期」节 · 仓储拆页后 `channel-import` tab 无页承载，2026-10-29 盘点发现）
- 十五期：本表 **D28**（「十五期」节 · 仓储/履约三端口径复审 A1–A9 + B1–B14，2026-10-29 开单，F1–F5 批次推进）
