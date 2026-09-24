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
