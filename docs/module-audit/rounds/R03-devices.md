# R3 审计：设备商品（10 页 + 设备详情动态页）

- 执行 AI / 日期：Qoder（本会话）/ 2026-10-31
- 代码基线：HEAD `c4ccfde9`（工作树干净；远端 `refs/heads/dev` 同步）
- 环境：本地 docker（trade `:18080`、nginx `:80` 提供 `/admin`，产物 = 本提交）；Postgres 库 `aicabinet` @15433
- 账号：超管 `13900000001`（受限角色快测留 R8）
- 🔴 工具：一律 Playwright 驱动本机真 Chrome（`channel:'chrome'`，1600×1000，headed）；内置浏览器视口 702px 不用于样式判断

## Step 0 清单重拉（`node docs/module-audit/step0-inventory.mjs` @ `c4ccfde9`）

```
admin 侧栏登记页合计：77
  概览：9 / 交易履约：4 / 设备商品：10 / 履约仓储：4 / 仓储：8 / 财务商户：16 / 增长风控：11 / 系统：15
无 perm 码（登录即可见）：/profile
菜单有、路由无（死入口）：无
兼容重定向（非页面）：/index.html /oper-logs /rbac
路由有、菜单未登记（人工判）：/print
consumer-mp：main=7 sub=16 total=23
merchant-mp：main=5 sub=30 total=35
```

与 PLAN 一致（设备商品 10 页，无漂移）。

**开轮先结的历史线索**：R1 遗留**线索 #2**（`/reports` 离线 5 vs 工作台 3）已复现并转正为 **MA-008**，本轮内修完（详见台账）。

## 分批（工作法第 1 步：>8 页强制分批，每批 ≤6 页）

| 批次 | 页面 | 状态 |
|------|------|------|
| 批 1（设备面） | `/devices` 设备管理 · `/devices/:id` 设备详情（动态页）· `/device-ops` 设备运维 · `/device-map` 投放地图 · `/device-kpi` 设备可用性 | 进行中 |
| 批 2（商品面） | `/repair-tickets` 维修工单 · `/skus` 商品管理 · `/sku-review` 选品诊断 · `/sku-vision` 识别入驻 · `/learning-library` 商品学习库 · `/vision-mappings` 识别映射 | 未开始 |

联动面（本模块要点的 mp 侧）：merchant-mp `devices` / `device-detail` / `pricing`；学习库与识别映射涉及**将邑链路**（真柜在否影响可测性 → 真机项单列）。

## 本轮已拍板口径（勿顺手对齐，见 PLAN「已拍板业务口径」）

- 开门柜判据 = `DeviceSlotService.isDoorCabinet`（将邑 BOUND），**不是** `device_type`；补货行恒 `slotId=null`。
- 在架 = `SkuOnShelf.isOnShelf` 单源；缺货/告警 = `SkuStockLevel` 单源。
- 下架 SKU 的柜×商品台账行**不随动清**，admin 端「可见可清」（CB-080）。
- 设备详情空字段：温度仅将邑隐藏；固件/IMEI 厂商未提供；序列号用厂商 SN。
- 🔴 R2 删过 E2E 夹具 ⇒ 本轮基线：设备 5 台（DEPLOYED 3 / INBOUND 1 / RETIRED 1，**当前全部离线**）、订单 13 笔、累计营收 ¥5.00。

## 逐页结果

### 页面：设备管理（`/devices`）｜选项卡：全部(5) / 在线(0) / 离线(5) / 可购买(0) / 未锁机(3) / 已锁机(2)

- HEAD / 环境 / 账号角色：`c4ccfde9` · 真 Chrome 1600×1000 · 超管
- 视图源码：`clients/admin-vue/src/views/devices/DeviceListView.vue`
- 联动端点：merchant-mp `devices` / `device-detail` / `pricing`（留 R9）

| 维度 | 结论 | 备注/问题 |
|------|------|-----------|
| 1 入口与权限 | ✅ | 批量按钮与「退款设置」带 `v-hasPermi="['ops:device:edit']"`；受限角色快测留 R8 |
| 2 列表与数据 | ✅ | 六个 tab 计数与 SQL **全等**：全部 5 / 在线 0 / 离线 5 / 可购买 0（在线且未锁）/ 未锁机 3 / 已锁机 2（`device_info`：total 5、online 0、sales_locked 2）；KPI 卡与 tab 同源且**带 hint 说明口径**（「未锁机 3 含离线柜」「可购买 0 在线且未锁」）；筛选全部服务端化：`lifecycleStatus=DEPLOYED`→3、`=IDLE`→0、`coopMode=SELF`→1、`routeCode=R-DEMO-01`→0、`q=将邑`→1，逐项等于 SQL 真值 |
| 3 按钮与操作 | ✅ | 🔴 批量危险操作三道闸实测：未勾选 → 按钮 **disabled**（不是点了才警告）；勾选 1 台 → 变可点 → `ElMessageBox.confirm`「将对 1 台设备执行「锁机」，确认继续？」→ **取消后 POST 数 = 0**；代码侧还有 `hasPerm` 前置闸（`DeviceListView.vue:936-956`） |
| 4 表单与校验 | ✅（观察项） | 「新建设备」8 字段（编号自动生成 EG+12 位并写明不可修改）；空提交 → **0 POST**（`缺少设备编号` 守卫）；观察：拦截形态是 toast，字段级红字为空、必填星号只有 1 个 ⇒ 满足「必填缺失能拦」的最低判据，**不领号**，若后续轮出现同类再统一提表单规范 |
| 5 样式与布局 | ✅ | 1600 宽正常；设备编号列 `min-width=140`；截图 `evidence/R03-devices-list.png` |
| 6 文案与口径 | ✅ | 生命周期走字典 `device_lifecycle`（IDLE 未投放 / INBOUND 入库 / DEPLOYED 投放 / RETURNING 返厂中 / RETIRED 退役），页内标签与字典一致、无裸码；点位坐标 hint 写明「选商户即视为部署 ⇒ 经纬度必填，补货签到靠坐标做地理围栏」——与 CB-074 签到口径同源 |
| 7 联动一致性 | ⚠️ 部分 | 行内「详情」跳 `/devices/500909625160`（7 个 tab：在售商品/资产与投放/远程运维/将邑对接/单柜定价/投放流水/关联单据）⇒ 本轮作为独立页测；merchant-mp 侧留 R9 |
| 8 控制台与网络 | ✅ | 全程 console 0 error（三个探针脚本各自收集） |
| 9 打印/导出/外链 | ✅ | 「导出」是**纯前端 CSV**（点击无网络请求），故不涉及 R2 拍板① 的服务端/前端两套表头问题 |

- 发现：无（本页九维过完，无新 MA 号）。
- 🔴 **一次「假缺陷」的排除记录**（值得所有接手 AI 看）：探针先用 `has-text("投放")` 选生命周期，结果「投放」和「未投放」两次都命中 **IDLE**（`has-text` 是子串匹配，下拉里「未投放」排在前面），于是看到「选投放也返回 0 条」——差点按 P1 领号。改用精确正则匹配后：投放→`lifecycleStatus=DEPLOYED`→3 条 = SQL 真值 ✅。**教训：Playwright 选 el-select 选项必须精确匹配文本，子串匹配会制造不存在的缺陷**（已写进本行下方交接注记）。
- 数据缺口：5 台设备当前**全部离线**（真柜未上电），故「在线/可购买」两态、远程运维下发、实时温度都只能测到空态；真机项进「需用户配合」。

### 页面：设备详情（`/devices/:id` 动态页）｜选项卡：在售商品 / 资产与投放 / 远程运维 / 将邑对接 / 单柜定价 / 投放流水 / 关联单据

- 取两柜对照测（一台将邑 BOUND、一台演示柜）：`EG943322621391`（slots 0 / 台账 3 全在架 / 订单 8 / 会话 62 / jy=BOUND）与 `500909625160`（slots 8 / 台账 6 **在架 0** / 订单 0 / 会话 2 / jy=RETIRED）
- 视图源码：`clients/admin-vue/src/views/devices/DeviceDetailView.vue` + `components/device/DeviceRelatedRecordsTab.vue` + `composables/device/*`

| 维度 | 结论 | 备注/问题 |
|------|------|-----------|
| 1 入口与权限 | ✅ | 从 `/devices` 行链接进入；头部 hint 交代四段结构（运营指标/基础信息/投放位置/运行状态） |
| 2 列表与数据 | ✅ | 在售商品：将邑柜「已上架 3/50」= SQL `inv_active=3`；演示柜「已上架 **0**/50」+ **CB-080 下架残留台账（6）** 区块在位 ⇒ 上轮拍板「不随动清、可见可清」在活栈成立；单柜定价：将邑柜 3 行、演示柜空态（与在架数一致） |
| 3 按钮与操作 | ✅ | 上架/刷新/重置价格/清除残留等均在位；本轮不提交写操作（避免污染共享库），清除按钮的确认闸在 CB-080 批次已实跑验过 |
| 4 表单与校验 | ✅（部分） | 资产与投放表单可读；点位坐标 hint 与签到地理围栏口径同源（选商户即视为部署 ⇒ 经纬度必填，后端 `createDevice` 亦 fail-closed） |
| 5 样式与布局 | ✅ | 1600 宽七 tab 无溢出；「共 N 条」链接与标题同行、字号降一级不加粗 |
| 6 文案与口径 | ✅ | 远程运维 tab 明写「将邑柜不支持运维远程开门与远程重启（协议无对应指令，重启类需现场处理）：开门请走补货调度 → 补货开门」——与开门柜判据（将邑 BOUND）一致，非缺陷；将邑对接 tab 用六步流水线（挂接→采集→审核→学习→映射下发→上架）+ 状态「已绑定/已退役」，与 `jiangyi_device.status` 一致 |
| 7 联动一致性 | ✅ | 投放流水空态正确（`device_lifecycle_event` 全表仅 1 条、两柜各 0）；关联单据与 `/sessions`、`/orders` 深链互通（实测跳 `?deviceId=` 得 62 条） |
| 8 控制台与网络 | ✅ | 全程 console 0 error；🔴 取数一律走 `softFallback`（`utils/soft-fallback.ts` 注释即「禁止 `.catch(() => [])` 静默空列表」，lessons #116）⇒ 接口失败会 toast，不会伪装成「暂无数据」 |
| 9 打印/导出/外链 | N/A | 本页无导出/打印 |

- 发现：**MA-009**（P3，关联单据两张表各截 8 条、`total` 早已取回却只用于空态判断 ⇒ 62 次开门的柜看着只有 8 次，且段级无查看全部入口）——轮内已修（`3f877459`+产物 `34068cd0`），正负向都验：将邑柜显「共 62 条 · 查看全部」并可跳 `/sessions?deviceId=`；未截断的「最近订单」（8=8）与演示柜（2 条）都**不显示**链接。
- 探针坑（记进交接注记）：`.section-more` 用 Playwright `click()` 会因内部滚动容器超时，改 `evaluate(el => el.click())` 可稳定触发；`allTextContents()` 会连隐藏 pane 一起取，判可见必须用 `:visible`。

### 页面：设备运维（`/device-ops`）｜无选项卡（列表 + 筛选）

- 视图源码：`clients/admin-vue/src/views/devices/DeviceOpsMonitorView.vue`；后端 `CompetitiveGapService.listDeviceOpsEvents`
- 九维要点：表头 9 列（事件ID/类型/级别/设备名称/设备编号/标题/详情/账龄/时间）；筛选 类型/级别/设备/关键词**全部服务端**；排序按钮切换 `sortDir=asc|desc` 走请求；导出 + 列设置在位；console 0 error。
- 发现两条，用户拍板后均已修：
  - **MA-010（P2）** 后端硬编码 `now-14d ~ now`（`CompetitiveGapService.java:376-380`）且页面无日期控件 ⇒ 运营把「共 54 条」当全量。**已加 `from`/`to`（都可空，都空仍回落 14 天 ⇒ 商户端理货-盘点复用行为不变）+ 前端「时间」daterange**；活栈验证：选 10-01~10-31 → 请求带 `from/to`、`共 80 条`；不选 `共 44 条`；同刻 SQL `all=80 / within14d=44` 完全一致。
  - **MA-011（P2）** 关键词是**前端本页过滤**（`filterByKeyword` + 页内条数顶替 total，MA-007 同族）⇒ 只在第 2 页的事件搜不到。**已服务端化**（`q` 参数，匹配 事件ID/设备编号/标题/详情），活栈验证：取第 2 页首条事件 ID 搜索 → `total=1`（修复前必 0）。
  - 🔴 集成踩坑（记进交接注记）：daterange 的 v-model 默认是 **Date 对象**，按 number 判 `Number.isFinite` 会永远不带参数；必须 `value-format="x"`。
  - 🔴 数据漂移说明：dev mock 会在**读接口**按需补演示事件（`ensureSyntheticOfflineEvents` / `ensureNoSalesEvents`），所以本页「共 N 条」在多次刷新间会小幅变化（本轮 73 → 80），属预期，不是回归。

### 页面：设备可用性（`/device-kpi`）｜无选项卡（KPI 卡 + 日期选择）

- 九维要点：hint 交代「默认当天实时口径；选择日期后查询该日快照」；KPI 卡 7 项（设备总数 5 / 离线事件 2 / 自动锁机 0 / 自动解锁 0 / 人工解锁 0 / 平均锁定时长 暂无样本 / 平均恢复时长 7.28h / 人工介入率 无解锁）；console 0 error；证据 `evidence/R03-device-kpi-pass.png`。
- 数值对真值：设备总数 5 = `count(*) from device_info` ✅；**离线事件 2 = 今日（Asia/Shanghai）新建的 `DEVICE_OFFLINE` 异常数** ✅（我先按「离线段重叠设备数」手搓 SQL 得 3，差点误报——真实口径是**当日新建事件数**，见 `DeviceAvailabilityKpiDto.offlineEvents` 注释；改按 mapper 谓词复算后精确等于 2）。
- 观察项（不领号）：`device_availability_kpi_daily` 最新行是 2026-10-24（日作业 17:10 UTC 写），今天无行 ⇒ 当天走实时计算，与 hint 一致；另 `ops_exception` 里 `DEVICE_OFFLINE` 有 4 行 `device_id` 为空，KPI 的**按设备离线时长**循环会跳过它们（`deviceId == null || isBlank() → continue`）⇒ 属数据质量问题，记给 R11 造数纪律（异常必须带设备）。

### 页面：投放地图（`/device-map`）｜无选项卡（地图 + 落点）

- 九维要点：工具条「查询 / 刷新 / 共 4 个柜机落点 / 详情」；底图用**高德栅格瓦片**（`webrd0x.is.autonavi.com`，外网依赖）；console 0 error；证据 `evidence/R03-device-map-pass.png`。
- 数值对真值：4 个落点 = `latitude/longitude 均非空` 的设备数 ✅（5 台中仅 `EG879147199289` 退役柜无坐标，正确不画点）。
- 待深测（批 1 收尾）：地图 marker 点击 → 详情跳转、无坐标柜的提示、外网瓦片不可达时的降级表现（本地能连外网，未断网验证）。

### 页面：设备报表（`/reports`，R1 已测页）｜本轮只补开轮发现的 MA-008

- 发现：**MA-008**（P2，`/reports` 与 `/dashboard` 同名 KPI「离线设备」5 vs 3，报表侧不标口径）
- 复现（同一次会话取数）：报表 KPI 读 `reports/devices?online=OFFLINE` → `total=5`；工作台读 `workbench-bundle.offlineDevices` → 3；SQL 真值 `online_status<>'ONLINE'` = 5，加 `lifecycle_status='DEPLOYED'` = 3（lifecycle 分布 DEPLOYED 3 / INBOUND 1 / RETIRED 1）。
- 根因两侧都是**刻意**的：`OpsWorkbenchQueryService.countOfflineDevices:631-643` 注释写明「仅投放柜离线（避免 INBOUND 孤儿柜抬高）」；报表列表本就是全设备台账 ⇒ **不改计数逻辑**。
- 修复：档 A 纯文案（沿用 R1「缺货建议 tab」先例），`deviceReportOfflineHint` 改为「全量口径（含未投放/已退役柜；工作台只算投放柜）· 点击筛选离线」。源码 `7ad59bbc` + 产物 `c4ccfde9`。
- 验证腿：真 Chrome 活栈复验 KPI 文本已渲染且值=5、同会话工作台=3、console 0 error；证据 `evidence/R03-reports-offline-kpi.png`。

### 页面：商品管理（`/skus`）｜选项卡：在售商品(3) / 所有商品(15)

- 视图源码：`clients/admin-vue/src/views/skus/SkuListView.vue`；接口 `/ops/admin/skus`
- 九维要点：筛选 关键词/类目/归属（直营·非直营·未分类）服务端化；工具栏 识别入驻/新建商品/批量下架/打印标签/导出/下载模板/导入/列设置；行操作 编辑/复制创建/更多操作；console 0 error。
- 数值对真值：`sku_catalog` 16 行 / ACTIVE 4 行，UI 显 **15 / 3** —— ✅ **不是缺陷**：`100011`（E2E归属测试商品）是 `is_deleted=true` 的**逻辑删除**行，MyBatis-Plus `@TableLogic` 自动排除；同理 `operationScope=NON_DIRECT` 查 0 也是对的（唯一非直营行即该逻辑删除行）。
  - 🔴 教训：做真值 SQL 必须带 `and is_deleted=false`，否则会造出「页面少了 1 条」的假缺陷（本次差点按 P2 领号）。
- 观察项：`100011` 是又一条 **E2E 遗留**（逻辑删除、不入列表），是否物理清理由用户定；与 R2 清的 E2E 订单同源。

### 页面：维修工单（`/repair-tickets`）

- 九维要点：筛选 状态/设备/优先级/故障类型（下拉只列现存 3 台设备 ✅ 与设备清理一致）；工具栏 批量指派/新建工单；表 9 列；`repair_ticket` 全表 **0 行** ⇒ 空态「暂无维修工单」+ `共 0 条` ✅；console 0 error。
- 未演练：新建工单/批量指派（写操作，本轮不提交）；无数据故「批量指派」的未勾选态无法观察。
- 待真机：故障类型里「门锁/制冷/供电」类真实工单要靠真机故障产生。

### 页面：选品诊断（`/sku-review`）

- 九维要点：行操作 建议下架/保留/更多操作；工具栏 批量下架/批量保留/运行诊断/导出/列设置；表 9 列（SKU/商品/分类/动销表现/销量/营收(元)/库存天数/评审状态/操作）；console 0 error。
- 数值对真值：接口 2 行（100014 NO_SALES 销量 0、100013 SLOW_MOVER 销量 2）与 SQL `cabinet_order_line` 近 30 天（仅 100013 有 2 件）**一致**；两行均 `PENDING` 待评审。
- 观察项（口径，不领号）：`营收(元)` 取的是**明细原价**（100013 = ¥7.00 = 2×¥3.50），而该 SKU 实收为 ¥3.50+¥1.50=¥5.00（其中一单 ¥2 券）。列名「营收」若按实收理解会高估；另外这两行是 10-21/10-23 的**持久化快照**，不点「运行诊断」不会刷新。是否改成实收口径见「需用户拍板」。

### 页面：识别入驻（`/sku-vision`）

- 定位：**导航页**（无表格/筛选），用 6 步流水线（挂接→采集→审核→学习→映射下发→上架）+ 说明「识别在将邑端侧、云端不做识别；有现成模型直接走第⑤⑥步」；工具条 6 个跳转（商品管理/采集台/学习库/识别映射…）。数据读 `/jiangyi/flow-summary`（linkedSkus 4 / boundCabinets 1 / activeMappings 1）；console 0 error。
- 结论：✅ 文案与 CB/H-27 口径一致（识别名称是厂商 classes 名、核对看「平台商品」中文名）。

### 页面：商品学习库（`/learning-library`）｜选项卡式四段（可学习商品 / 柜机 / 模型 / 最近学习）

- 数值对真值：API `learning-library` = `learnableProducts` 1（康师傅冰红茶 100014）、`learnableSkus` ≥1（100018 雪碧 500ml）、`models` 0、最近学习 2 条；UI 四段 `共 1 / 1 / 0 / 2 条` ✅ 完全一致。
- 说明文案准确：「租户级学习资产，一次学习所有柜机共用；能不能卖仍逐柜」——与识别映射页口径一致。
- 待真机：`开始采集`（柜前拍摄、期间停营业）/`触发学习`（厂商云训练）都要真柜在线；当前真柜离线 ⇒ 只验了入口与说明。

### 页面：识别映射（`/vision-mappings`）

- 交互：**必须先选柜机**才出表（空态文案「选择一台将邑柜机查看其识别映射与模型同步状态」）；柜机下拉只列将邑柜 3 台。
- 数值对真值：选将邑测试机 → `共 71 条` = SQL `jiangyi_class_mapping` 71 行 ✅；状态列中文（首页 20 行全「已停用」= 现库 1 ACTIVE / 70 DISABLED 的合理切片）；两张表（映射表 8 列 + 模型同步表 6 列）；console 0 error。
- 观察项（疑问，未领号）：首页样本行「识别编号 = **-74**、识别名称 = —、来源 = 手工、状态 = 已停用」——负 classId 疑似导入占位；`classId` 语义见 H-27（textName 排序行号），是否应显示负值需与将邑口径确认，勿顺手改。

## 发现汇总

| 级别 | 数量 | 编号 |
|------|------|------|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 1 | MA-008（轮内已修） |
| P3 | 1 | MA-009（轮内已修） |
| P2 | +2 | MA-010（from/to 已加）· MA-011（关键词服务端化） |
| 批 2 | 0 新缺陷 | 六页数据全部与真值一致（两处「差 1/0 条」经查是我真值 SQL 漏了 `is_deleted` 与逻辑删除行） |

## 已删除数据留档（拍板：清两台遗留柜，2026-10-31）

用户拍板「两台遗留柜要清」。删除前按 33 张 `FK → device_info` 的表逐张计数，再在**单事务**里按依赖顺序删除（第一次执行被 `replenishment_task_line` 外键挡下并**整体回滚**，补上该表后成功）：

```sql
BEGIN;
DELETE FROM dispute_ticket WHERE session_id IN (SELECT session_id FROM shopping_session WHERE device_id IN ('719325384517','EG879147199289'));
DELETE FROM cabinet_order WHERE device_id IN (...);
DELETE FROM shopping_session WHERE device_id IN (...);
DELETE FROM replenishment_task_line WHERE task_id IN (SELECT task_id FROM replenishment_task WHERE device_id IN (...));
DELETE FROM replenishment_task WHERE device_id IN (...);
-- 再对 33 张 FK 表逐张 DELETE ... WHERE device_id IN (...)
DELETE FROM device_info WHERE device_id IN (...);
COMMIT;
```

删除量：`device_info` 2（剩 3 台）· `cabinet_order` 5（**全部 0 元**）· `shopping_session` 7（含 2 DISPUTED）· `dispute_ticket` 2 · `replenishment_task_line` 6 + `replenishment_task` 1 · `device_slot` 16 · `device_sku_inventory` 6 · `device_temperature_reading` **64001** · `device_ops_event` 27 · `ops_exception` 18 · `device_lifecycle_event` 1。

**删除后新基线（后续轮次以此为准）**：设备 3 台（演示智能柜 `/ 门店二号柜 CAB-OTHER / 将邑测试机`，全部离线）· 订单 **8**（零元 6，营收仍 **¥5.00**）· 会话 **64** · 争议 OPEN **20** · 运维事件 **73**（近 14 天 37）· 异常 OPEN **29** · 温度读数 0。

业务行原始 JSON（可原样重插，`.tmp` 里的副本不入库）：

```json
{"orders":"见 .tmp/r3-clean-orders.json（5 行，均 total_amount_cents=0 / status=PAID / 2026-10-08）"}
{"sessions":"见 .tmp/r3-clean-sessions.json（7 行：5 COMPLETED 关联上述订单 + 2 DISPUTED 无订单）"}
{"disputes":"见 .tmp/r3-clean-disputes.json（2 行，随会话一并删除）"}
```

## 需用户拍板

1. **`/device-ops` 的 14 天窗口要不要放开或做成可选日期范围**：档 A 文案已把口径说清（MA-010），但 14 天前的事件在本页永久不可查。放开会影响**商户端理货记录-盘点**（复用同一查询，CB-050）。我倾向：加 `from/to` 参数、默认仍是 14 天（行为不变），前端加日期控件——这样两端可见性都不变，运营需要时能自己拉宽。
2. ~~两台遗留柜要不要清~~ → **已拍板：清**（2026-10-31 执行完毕，留档见上节）。
3. ~~选品诊断「营收(元)」口径~~ → **已拍板：成实收或列头写明「原价营收」**。采用**列头标注**（改实收需按行分摊订单级优惠，风险大且会改写历史快照）：列头 `营收(元)` → **`原价营收(元)`**（CSV 表头同改），页头 hint 补「＝明细原价合计（未扣券/优惠），与财务实收口径不同」。源码 `b5e7cd28` + 产物 `6e1937fe`，活栈复验列头/hint 均生效。
4. ~~E2E 商品与备份表要不要物理清~~ → **已拍板：物理清 / 没用就删**，2026-10-31 执行：
   - `sku_catalog.100011`（`E2E归属测试商品`，原为逻辑删除）：删前查 `device_sku_inventory` / `device_slot.assigned_sku_id` / `cabinet_order_line` / `device_sku_lot` / `device_sku_price` / `jiangyi_class_mapping` **全部 0 引用**；已物理删除 ⇒ `sku_catalog` **16 → 15**（不再有 `is_deleted` 行，「所有商品」= 全表）。原始行 JSON 见 `.tmp/r3-clean-sku100011.json`（`price_cents` 350 / `category` 饮料 / `barcode` 6901234567890）。
   - DROP 三张备份表：`sku_vision_mapping_bak_20261011`（24 行）、`sku_catalog_vision_cols_bak_20261011`（9 行）、`_bak_jiangyi_class_mapping_20261021`（69 行，现表 71 行）。
     **依据**：前两张在**生产迁移 `V344__production_demo_seed_guard.sql:48-49` 里本来就会 DROP**（注释：内容 100% 是演示 SKU 快照、无保留价值），dev 只因守卫条件未触发而残留；第三张是 10-21 手工快照、代码零引用、已被现表取代。删后 `pg_tables` 中 `%bak%` 计数 = 0。

## 需用户配合（真机）

- 真柜（EG943322621391）当前是否上电在线直接影响：设备详情实时字段（温度/门磁）、识别映射下发、学习库采集。本轮先按「设备离线」状态测可读面，真机项集中约一次。

## 修复批次建议

- 本轮 P0/P1 = 0 ⇒ **无轮末批次**。四条 MA（008 P2 / 009 P3 / 010 P2 / 011 P2）全部轮内修完并各自带活栈验证。
- 批 2 六页零新缺陷：所有「差 1 条 / 0 条」都经查是我真值 SQL 漏 `is_deleted`（逻辑删除）所致，页面正确。
- 门禁化收口（工作法第 8 步）：**`check:list-filter-server-side` 已上线**（用户拍板「先实现修复，再开 R4」）。首扫 50 个 `fetchPage` / 命中 4 处：仅 `/ad-campaigns` 是真缺陷（MA-012，已修），另 3 处进棘轮白名单并写明依据（`/orders` overdueOnly 后端已扫的安全网、`/sessions` 焦点会话去重、`/roles` 接口无分页的全量内存过滤）。门禁带 9 条自测断言 + 聚合链接线（wiring 门禁：82 引用 / 96 个 check 脚本全部可达）。

## 给下一轮的交接注记

- 🔴 终端里直接 `curl` 中文 query 会按 **GBK** 发出去，服务端 UTF-8 匹配恒 0（曾误判「中文关键词搜不到」）；验中文必须用 `node`/`--data-urlencode` 走 UTF-8。
- 🔴 Element Plus `type="daterange"` 的 v-model 默认回 **Date 对象**；按 number 判 `Number.isFinite` 会永远不带 from/to ⇒ 必须 `value-format="x"`。
- 🔴 `/device-ops` 两次刷新条数会变：dev mock 在读接口里按需补演示事件（`ensureSyntheticOfflineEvents`/`ensureNoSalesEvents`），不是回归。
- 🔴 Playwright 点 `el-select` 选项**必须精确匹配文本**（`.filter({ hasText: new RegExp('^\s*X\s*$') })`），`has-text("投放")` 会命中「未投放」⇒ 曾据此差点误领一条 P1（详见 `/devices` 维度 2 下方的排除记录）。同理：`waitForURL(/\/admin\//)` 会把 `/admin/login` 判成已登录。
- 探针里区分「列表请求」与「KPI 探针请求」：本页 KPI 是 8 个 `size=1` 的并发计数请求且**都带当前筛选参数**，只按 URL 前缀过滤会把它们误当成列表请求。
- 线索 #2 已转正为 MA-008 并闭合 ⇒ `ISSUES.md` 的「待复核线索」区现在只剩历史条目。
- 🔴 **真值 SQL 必须带 `is_deleted=false`**：`sku_catalog`（16 行含 1 条逻辑删除）差点被我算成「页面少一条」的 P2。（同类逻辑删除字段在各表命名不统一：`is_deleted`。）
- 🔴 备份表残留：`sku_vision_mapping_bak_20261011` / `sku_catalog_vision_cols_bak_20261011` / `_bak_jiangyi_class_mapping_20261021` 仍在库（不影响页面，列进待拍板）。
- R2 遗留的两条待办仍在：`/orders` 样式维度补 1600px 复测；`check:list-filter-server-side` 门禁候选（注意 `OrderListView` 的 `overdueOnly` 页内安全网要进 allowlist）。
