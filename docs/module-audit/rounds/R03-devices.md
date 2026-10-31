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

### 页面：设备报表（`/reports`，R1 已测页）｜本轮只补开轮发现的 MA-008

- 发现：**MA-008**（P2，`/reports` 与 `/dashboard` 同名 KPI「离线设备」5 vs 3，报表侧不标口径）
- 复现（同一次会话取数）：报表 KPI 读 `reports/devices?online=OFFLINE` → `total=5`；工作台读 `workbench-bundle.offlineDevices` → 3；SQL 真值 `online_status<>'ONLINE'` = 5，加 `lifecycle_status='DEPLOYED'` = 3（lifecycle 分布 DEPLOYED 3 / INBOUND 1 / RETIRED 1）。
- 根因两侧都是**刻意**的：`OpsWorkbenchQueryService.countOfflineDevices:631-643` 注释写明「仅投放柜离线（避免 INBOUND 孤儿柜抬高）」；报表列表本就是全设备台账 ⇒ **不改计数逻辑**。
- 修复：档 A 纯文案（沿用 R1「缺货建议 tab」先例），`deviceReportOfflineHint` 改为「全量口径（含未投放/已退役柜；工作台只算投放柜）· 点击筛选离线」。源码 `7ad59bbc` + 产物 `c4ccfde9`。
- 验证腿：真 Chrome 活栈复验 KPI 文本已渲染且值=5、同会话工作台=3、console 0 error；证据 `evidence/R03-reports-offline-kpi.png`。

## 发现汇总

| 级别 | 数量 | 编号 |
|------|------|------|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 1 | MA-008（轮内已修） |
| P3 | 0 | — |

## 需用户拍板

- （待填）

## 需用户配合（真机）

- 真柜（EG943322621391）当前是否上电在线直接影响：设备详情实时字段（温度/门磁）、识别映射下发、学习库采集。本轮先按「设备离线」状态测可读面，真机项集中约一次。

## 修复批次建议

- MA-008 已轮内修完。批 1/批 2 发现的 P2/P3 按节奏轮内修；P0/P1 攒到 R3 轮末批次。

## 给下一轮的交接注记

- 🔴 Playwright 点 `el-select` 选项**必须精确匹配文本**（`.filter({ hasText: new RegExp('^\s*X\s*$') })`），`has-text("投放")` 会命中「未投放」⇒ 曾据此差点误领一条 P1（详见 `/devices` 维度 2 下方的排除记录）。同理：`waitForURL(/\/admin\//)` 会把 `/admin/login` 判成已登录。
- 探针里区分「列表请求」与「KPI 探针请求」：本页 KPI 是 8 个 `size=1` 的并发计数请求且**都带当前筛选参数**，只按 URL 前缀过滤会把它们误当成列表请求。
- 线索 #2 已转正为 MA-008 并闭合 ⇒ `ISSUES.md` 的「待复核线索」区现在只剩历史条目。
- R2 遗留的两条待办仍在：`/orders` 样式维度补 1600px 复测；`check:list-filter-must-be-server-side` 门禁候选（注意 `OrderListView` 的 `overdueOnly` 页内安全网要进 allowlist）。
