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
| D26 | P2 | open（A 类已清，仅剩 `ReplenishmentView`） | 运营后台仍有文件对**开门柜**讲「货道」：恒空列 / `待分配`·`无`·`暂无` 兜底 / 按货道粒度的文案 | 下表 A 类逐条 `file:line`；判据来自 2026-10-28 拍板 | 2026-10-29：A 类 8 处落地（见下「处置」与修复日志） |

### A 类 · 对开门柜会误导（✅＝2026-10-29 已改；`ReplenishmentView` 待缺货新口径）

| 位置 | 现状 | 为什么说它误导 |
|------|------|----------------|
| ✅ `components/warehouse/WarehouseOutboundsTab.vue:84` | `label="货道"`，单元格 `{{ row.slotId \|\| '无' }}` | 出库明细行=补货任务行；开门柜恒 `null` ⇒ 整列恒显「无」 |
| ✅ `views/print/PrintView.vue:35` `:53` | 拣货单表头「货道」+ `{{ line.slotId \|\| '待分配' }}`；脚注「请按单核对批次与货道」 | 开门柜的货**永远不会分配货道**，「待分配」把结构性缺失说成流程未完成 |
| ✅ `views/orders/OrderListView.vue:181` `:626` `:812` | 商品列内嵌货道展示 + 明细 `prop="slotId" label="货道"`（`\|\| '暂无'`） | 将邑柜订单走识别结算，无 slot；`utils/order-line-summary.ts:9` 靠后端 `·货道A1` 字面量解析，开门柜订单串里根本没有该段 |
| ✅ `views/disputes/DisputeListView.vue:587` | 争议明细 `prop="slotId" label="货道"`（`\|\| '暂无'`） | 同上，订单行的 slot 对开门柜恒空 |
| `views/replenishment/ReplenishmentView.vue:382` `:576` `:595` `:738` `:946` `:952` `:1121` `:1600` | 补货建议表 `prop="slotCode"`、空态「当前无缺货/低库存货道」、CSV 表头「货道」、「货道已满请先下架」、确认文案「设备×货道 粒度」 | 2026-10-28 已拍板：开门柜补货行恒 `slotId=null`，与残留货道无关 ⇒ 这些把「货道」当补货单位的表述对将邑柜不成立（**缺货新口径**本身也在待拍板清单里） |
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

**待拍板（三项 → 2026-10-29 已拍板 1、2；剩 3）**：
1. ~~A 类「恒空列」是删列还是按柜型条件显示~~ → **拍板删列**：这批界面服务的柜机固定都是将邑开门柜（用户 2026-10-28 定），不需要行级柜型判据，也就不存在「货道柜仍展示」的分支。
2. ~~`PrintView` 的「待分配」兜底词~~ → 随删列消失，不再需要选「不适用」还是条件显示。
3. **仍待拍板**：`ReplenishmentView` 的补货建议是否只对货道柜显示货道列（与「缺货新口径」那条是同一件事，等新口径定了再一起改）。

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
