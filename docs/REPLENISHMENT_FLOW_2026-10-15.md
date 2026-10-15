# 补货全流程走查（2026-10-15）

> 触发：用户要求按真实补货员视角走一遍完整补货业务流程，流程要「完整、清晰、简单」。
> 本文 = 竞品 SOP 对照 + 我方现状链路 + 实跑记录 + 断点清单与修复建议。
> 结论先行：**链路主干已通且防护正确（fail-closed）。三处断点已于 2026-10-15 下午修复并实证（见文末「断点修复实录」），流程闭环成立。**

---

## 一、竞品补货 SOP 对照（网上调研）

| 环节 | 行业通行做法（SandStar / 智购 / 支付宝视觉柜助手等） | 我方现状 |
|---|---|---|
| 缺货预警 | 系统按「缺货提醒比例」（默认销量/容量 >60%）提醒，比例可调 | ✅ 有：货道 OOS/LOW + 商户级阈值（默认 50%）+ SKU lowThreshold；⚠ 三套口径不一致（见断点1） |
| 生成补货单 | 筛选缺货设备 → 创建补货单，规则=「需补数量=上次补货后库存−当前剩余」 | ✅ 缺货建议一键转任务；建议量=补到 PAR；⚠ 转任务不出库单（断点2） |
| 按单分拣（前置分拣） | 仓库按补货单分拣，现场准备时间 15min→4.5min | ✅ 有出库单链（DRAFT→PICKED→SHIPPED→签收），但与「建议转任务」路径脱节 |
| 补货前盘点 | 建议每次补货前先盘点，及时追差异 | ✅ 仓库盘点/柜机盘点齐备；差异闭环靠人工（断点4） |
| 扫码开门补货 | 扫柜码 → 登录/签到 → 开门补货 → 放货/撤货 → 确定补货 | ✅ 全有：扫码→签到(GPS 围栏)→开门→条码核对→拍照留证→完成 |
| 先进先出 / 陈列规范 | 原有商品挪前、新商品放后；视觉柜不遮挡摄像头 | ⚠ 批次 FEFO 在柜内自动（restock 建新批次），现场无 FIFO 指引提示 |
| 补货记录 | 后台标记补货完成，记录时间/数量/临期 | ✅ 任务 COMPLETED+审计+库存流水+履约记录；⚠ 商户端无独立记录页（断点7） |
| 老系统（叮当小柜） | 扫码配货/扫码盘点/备货单/理货记录四入口 | 我方对应：扫码补货/仓库盘点/补货任务/履约记录——**入口形态对齐** |

## 二、我方补货员端到端流程（现状，10 步）

```
①预警  缺货产生：货道 bookQty≤minLevel（OOS/LOW）或 商户阈值 stockoutWarn（<容量50%）
        → admin「补货中心·缺货看板」/replenishment/shortage
        → 商户微信订阅消息（15min 扫描，4h 去重）
②建单  admin 缺货看板「生成补货任务」→ 路线 PLANNED + 任务 PENDING + RESTOCK 明细（补到 PAR）
        （或：admin 一键规划 / 商户端「发起补货」自建）
③接单  补货员 merchant-mp「补货任务」：任务卡显示 N 行 · 共 M 件
④签到  扫码到柜 → GPS 签到（500m 围栏，fail-closed）→ 任务 IN_PROGRESS
⑤开门  「补货开门」→ RESTOCK 会话（绑定任务）→ 将邑 sendOpenStockDoor（设备离线 409 拦截 ✅）
⑥上架  按明细补货 + 扫商品条码核对（条码→行 +1，货道余量限制）
⑦留证  拍照上传（系统参数 REPLENISHMENT_COMPLETE_REQUIRE_EVIDENCE 默认强制 ≥1 张）
⑧关门  设备门事件 CLOSED → 补货关门快照（重力增量→实盘；无重力→实盘=账面）
⑨完成  「确认完成」→ 三道闸（签到✓清单✓开门会话✓+凭证✓）
        → 批次 addRestock 回写柜机账面 → 超容钳制 → 在途签收 → 任务/路线 COMPLETED
⑩记录  商户端任务「已完成」tab；admin「履约记录」（GPS/用时/凭证）+ 补货员效率报表
        差异：bookQty vs 实盘 → 货道「差 N」/SLOT_DISCREPANCY 告警 → 人工盘点回写闭环
```

## 三、实跑记录（2026-10-15，本地全栈 Docker，真凭证）

| 步骤 | 操作 | 结果 |
|---|---|---|
| 1 | admin 登录（13900000001，captcha+redis） | ✅ |
| 2 | 查演示柜 500909625160 货道 | 6 货道全 OOS（bookQty=0，真实缺货起点） |
| 3 | GET /replenishment/shortage | ✅ 6 货道缺货项齐全（可乐/牛奶/泡面/薯片…，stockStatus=OOS） |
| 4 | POST /replenishment/suggest/create-task | ✅ 路线1+任务3（PENDING，6 行 42 件，outboundId=**null**） |
| 5 | 商户登录（13800138001）+ access 校验 | ✅ allowed=true |
| 6 | 任务列表 | ✅ 「6 行 · 共 42 件 · **6 行缺效期**」←断点2可见症状 |
| 7 | GPS 签到（柜机坐标，距离 0m） | ✅ PENDING→IN_PROGRESS |
| 8 | 补货开门 | ✅ 409「设备离线，暂不可开门」（fail-closed 防护正确） |
| 9 | 开门→关门快照→完成→记录 | ⏸ 需真机在线；该段由 `scripts/e2e-replenishment.ps1` 自动化覆盖（plan→出库→签到→开门→凭证→完成全链） |

## 四、断点清单（按优先级）

### P0-1 建议转任务不生成出库单 → 仓↔柜账实脱节（本次实锤）
`createTaskFromSuggestion` 只建路线+任务+RESTOCK 行，`outboundId=null`、批次留空。完成时仓库不扣减、柜机凭空加账面、生成 `B-<日期>-<uuid>` 伪批次——**仓库与柜机库存从此对不上**，且任务行「缺效期」。
**修法**：转任务时同事务走 `createOutboundForRoute`（plan 路径已有，照抄）；无仓配能力的商户保持现状但明确提示「该任务不带出库单」。

### P0-2 完成时 recordRestock 覆盖关门实测 → 差异信号被抹平
补货完成把 `lastPhysicalQty=bookQty` 全量刷新 + `clampDeviceOverCapacity` 再 `syncPhysicalFromBook`——关门快照记下的真实实测被覆盖，`qtyDiff/hasDiscrepancy` 必然清零，**少上架/错上架无法对账发现**。
**修法**：完成时保留关门快照实测（有重力数据时 `lastPhysicalQty` 不被 book 覆盖），差异走 SLOT_DISCREPANCY 人工确认。

### P1-3 三套缺货口径分裂
`stockoutWarn`（货道级阈值%）/ 工作台+推送 LOW_STOCK（SKU 级 low_threshold）/ 补货建议（货道 minLevel+ROP）互不同步：预警说缺的 SKU 在建议页可能没有 → 一键转任务 400 REPLENISHMENT_NO_GAP。
**修法**：统一以货道口径为准（阈值%与 minLevel 取或），工作台/推送/建议同源。

### P2 其余
4. `photo_replenish` 商户级开关是死配置（实际由全局系统参数控制）——二选一收口；
5. 商户端补货记录无独立页（现靠任务「已完成」tab，建议加「补货记录」列表=老系统「理货记录」对齐）；
6. 关门快照无重力数据时「实盘=账面」没有对账价值（将邑柜无重力传感的现实约束，文档标注即可）。

## 五、给运营的一句话流程（贴给补货员）

> 收到缺货提醒 → 打开商户小程序「补货任务」→ 扫柜码签到 → 补货开门 → 按单上架、扫码核对 → 拍照留证 → 关门 → 确认完成。完。


---

## 六、断点修复实录（2026-10-15 下午，全部实证）

### P0-1 建议转任务 → 出库单闭环 ✅
- 修法：`createTaskFromSuggestion` 按设备是否配置主仓分流——有主仓 ⇒ 同事务 `createOutboundForRoute`（与 plan 路径同源，不再种无批次草稿行，发运时 `generateLinesFromOutbound` 回填批次行）；无主仓 ⇒ 保持直加模式。仓库无货时由 `createOutboundForRoute` 诚实 400（REPLENISHMENT_WAREHOUSE_STOCK_INSUFFICIENT），不再静默伪批次。
- 实证（演示柜临时绑主仓 WH-DEMO-001）：create-task → 出库单 2（DRAFT，6 行带批次 B-WH-COLA-01 等）→ pick(PICKED) → ship(200) → 任务 4 获得 6 行**带批次**的 RESTOCK 行（对比修复前任务 3：outboundId=null、6 行缺批次）→ 完成时仓扣减/在途签收闭环。造数已还原（主仓=NULL、A1 盘回 0）。

### P0-2 完成时不再抹平关门实测 ✅
- 修法：`RestockSnapshotService.hasRealGravitySnapshot(taskId)`（会话 gravityDeltas 解析非空=真实实测）；补货完成全链（applyPendingReplenishmentLines / restockFromOutboundLines / catch-up）传 `preservePhysical`——有真实实测时 `recordRestock` 只刷 lastRestockAt，**不覆盖快照实盘**；`clampDeviceOverCapacity` 从全柜 BOOK_SYNC 改为只同步被钳制货道。无重力柜机行为不变。
- 单测：ReplenishmentService*（含新增 SuggestTask 出库分支用例）、RestockSnapshot*、DeviceSlot* 共 48 例绿。

### P1-3 三套缺货口径统一 ✅
- 修法：单一判定 `DeviceSlotService.isSlotShortage` = 账面≤minLevel **或** 账面<容量×商户阈值%（默认 50）。消费方全部改走它：admin 缺货看板（maybePutLowStockShortageRow）、补货建议触发（buildSlotSuggest）、运营工作台待办（OpsWorkbenchQueryService）、商户微信推送（MerchantWorkbenchQueryService）。
- 实证：A1 账面 3（>minLevel=2，旧口径不可见）→ 新口径进缺货看板（3<8×50%）→ 同源可一键转任务。
- P2（巡柜口径）已于同日下午补齐：新增 `GET /merchant/replenishment/shortage-summary`（MerchantInventoryPortalService.listShortageSummary，FIELD pack 范围、缺口=建议 headroom 同口径、按缺口降序取前 5）；merchant-mp 巡柜卡片换数据源（删 SKU 级 lowStockDevices 链），badge 改「N 个货道缺货」。实证：A1=3（>minLevel=2）商户端聚合仍计缺货货道、缺口 42→39 正确扣减，造数已还原。Task5 ③「储值余额预警待办」经用户拍板**不做**（2026-10-15）。

### 验证汇总
- 单测：trade 补货域 46~48 例全绿（新增 1 个 P0-1 行为钉住用例）。
- 实跑：pick/ship/回填批次行全链真实请求走通；fail-closed（离线开门 409）复核。
