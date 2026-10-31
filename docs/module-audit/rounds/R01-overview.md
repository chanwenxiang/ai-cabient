# R01 审计：概览 9 页

- 执行 AI / 日期：Qoder / 2026-10-31（跨多段会话）
- 代码基线：HEAD `0eb90b49`（工作树除本文档与 evidence/ 外干净）
- 环境：本地 docker 全栈（trade:18080、vite dev :3000、postgres/redis）；用户分析修复轮重建容器 `ai-cabinet/trade-service:local`
- 账号：运营超管 13900000001（图形验证码登录，redis 取码）

## Step 0 清单重拉（以代码为准）

概览菜单（`menu.ts` BASE_NAV）实际 9 页，与 PLAN 一致：

运营工作台 `/admin` ｜ 运营大屏 `/big-screen` ｜ 数据分析 `/analytics` ｜ 客流坪效 `/footfall` ｜ 设备报表 `/reports` ｜ 财务毛利 `/finance` ｜ 销售报表 `/sales-reports` ｜ 库存健康 `/stock-health` ｜ 用户分析 `/user-analysis`

## 逐页结果

> 每页按 page-checklist 9 维记录；本报告只展开有内容的维度，其余维度无异常不赘述（全页面均过：控制台无错误仅 vite dev 调试信息、无样式错位、文案无错别字）。

### 页面：运营工作台 `/admin`（DashboardView.vue，993 行）

| 维度 | 结论 |
|------|------|
| 列表数据 | ✓ 与后端 `workbench-bundle` 原始载荷逐项核对：在线率 0.0% 为真（deviceTotal=3, deviceOnline=0，设备故障+模拟器不在线） |
| 联动一致性 | ✓ 「入口合计 63 vs 四分区 64」为刻意设计（进件入口只保留区卡按钮，源码注释） |
| 按钮操作 | ✓ 「仅紧急」筛选 27 条全为紧急；分页 3×10=27 |

**发现**：无缺陷。

### 页面：运营大屏 `/big-screen`（BigScreenView.vue）

| 维度 | 结论 |
|------|------|
| 样式布局 | 🔴→✅ P3（已修，Task 6）：1044px 窄视口标题「AI 开门柜·售货机运营态势大屏」nowrap+字距在 68px header 网格溢出，横穿 KPI 卡。修复 ≤1280px 改双行 flex；558/1043/1600px 三档零重叠、宽视口无回归（同源 iframe 媒体查询法）。证据 evidence/R01-big-screen.png、R01-big-screen-narrow-fixed-558.png |
| 文案口径 | 🔴→✅ P2（已修，Task 5）：品类构成图例显裸字典码「1」。根因 `sku_catalog.category` 存 `category_code` 字典值「1」，`CompetitiveGapService.aggregateByCategory` 原样透传。修复：注入 `SysDictService.labelMapOf("category_code")` 翻译（1→饮料，空→未分类），live 验证 dim=CATEGORY 返回 `dimLabel:"饮料"`，钉测 `salesReport_categoryDim_translatesDictCodeToLabel` |
| 列表数据 | ✓ 「暂无线路营收」为空态文案（非缺陷）；「品类构成 100% 零营收」有真值支撑（近 30 天营收 ¥8.50） |

**发现**：2 缺陷均已修并推送（`355bb308` + `5a62f77e`/`0eb90b49`）。大屏 1920px 严格档未单测（1600px iframe 已回归）。

### 页面：数据分析 `/analytics`

| 维度 | 结论 |
|------|------|
| 列表数据 | ✓ 快照与后端统计全对上（开门成功率 21.7%、累计营收 ¥8.50、22 待审） |
| 联动一致性 | ✓ 范围切换生效、刷新正常 |

**发现**：无缺陷。

### 页面：客流坪效 `/footfall`

| 维度 | 结论 |
|------|------|
| 列表数据 | ✓ 28 开门 / 0 单 / 0% 转化自洽 |
| 文案口径 | ✓ 0/0 显 0.0% 与表头口径一致 |

**发现**：无缺陷。

### 页面：设备报表 `/reports`

| 维度 | 结论 |
|------|------|
| 列表数据 | ✓ ¥8.50 累计营收一致、停售 2 与工作台对齐 |
| 文案口径 | ⚠️ **线索（未入账）**：「离线设备」同词两口径——本页「离线设备 5」（全部设备）vs 工作台「离线设备 3」（deployed/投放柜口径）。按入账前提须先实跑复现两处取数函数体再定缺陷或口径标注；见 ISSUES.md 待复核线索 #2 |

**发现**：1 线索转 ISSUES 待复核，未领号。

### 页面：财务毛利 `/finance`（FinanceView.vue）

| 维度 | 结论 |
|------|------|
| 列表数据 | ✓ 累计快照与 `GET /api/v2/ops/admin/finance/stats` 交叉核对一致（累计营收 ¥8.50 / 成本 ¥4.00 / 毛利 ¥4.50；今日全 0 与当日无单一致） |
| 按钮操作 | ✓ 「固化昨日毛利」确认弹窗文案明示不可回溯（「固化后不可回溯重算，确认执行？」）；**取消 → 零 POST**（performance 资源列表复核，无任何写端点被调用），写操作确认门有效 |
| 打印导出外链 | ✓ 「日资金账单见资金账单菜单」跳转指引正确 |

**发现**：无缺陷（本页在会话前段曾被跳过，本段补齐全部验证）。

### 页面：销售报表 `/sales-reports`（SalesReportsView.vue，647 行）

| 维度 | 结论 |
|------|------|
| 列表数据 | ✓ 5 维度实跑（PRODUCT/CABINET/MERCHANT/CHANNEL/MARGIN），dimKey/dimLabel 正确；KPI tile 与 summary sidecar 一致（订单 2 · 共 1 行 / ¥7.00 / 毛利率 42.9%）；fetchSeq 竞态守卫在位 |
| 联动一致性 | ✓ 服务端多列表头排序（sortBy=revenueCents&sortDir=desc 网络证实）；维度行跳转 /skus?keyword=100013、/devices/{id}、/merchants?tab=splits 权限门（canAccessPath）在位 |
| 打印导出外链 | ✓ 有勾选走页内 CSV、无勾选走后端导出（200 text/csv，携带当前筛选 fromDate/toDate/deviceId）；注意接口参数是 `fromDate/toDate` 非 from/to |
| 表单校验 | ✓ 快捷区间 today/7d/30d、默认近 7 天（避免今日无单整页空态） |

**发现**：无缺陷。（品类裸字典码缺陷在本页 dim=CATEGORY 同样命中，随 Task 5 后端修复一并解决。）

### 页面：库存健康 `/stock-health`（StockHealthView.vue，691 行）

| 维度 | 结论 |
|------|------|
| 列表数据 | ✓ 2 行 LOW（EG943322621391，SKU 100014/100015）；KPI 与 sidecar 一致；断货筛选空态「暂无异常库存」正确 |
| 按钮操作 | ✓ 行操作图标按钮（设备/补货）渲染且带 aria-label；**「一键补货规划（1 台）」深链验证通过**：跳 `/admin/replenishment?tab=shortage` 后 URL 中 plan/deviceIds 被清是**设计行为**（`maybeAutoPlanFromQuery` 消费后 `syncRouteQuery` 收窄，2026-09-26 UAT 教训注释在位），实际断言=规划弹层「规划补货路线」已打开 ✓ |
| 表单校验 | ✓ 报损弹窗责任方留空合法（V311：null≠NONE，取消即放弃）；`inputValidator` 失败保持对话框打开不误判取消 |
| 打印导出外链 | ✓ 导出有勾选走页内 CSV、无勾选走后端 |

**发现**：无缺陷。**未 exercised**：NEAR_EXPIRY 行的「下架/报损」写操作（dev 数据无临期 lot，链路后端另有单测覆盖）。

### 页面：用户分析 `/user-analysis`（UserAnalysisView.vue）

| 维度 | 结论 |
|------|------|
| 列表数据 | 🔴→✅ MA-001（P2，已修）：「30 天」口径 KPI 随所选窗口漂移——days=7 时活跃/新增「30 天」显 0（SQL 真值 2）、days=90 时显 24（虚增）。根因 `computeWindowMetrics` 把 active30 与 active7 在同一 days 窗口循环计数。修复=三窗口独立计数（7d/30d 固定 + days 只管订单/营收/客单价），竞品口径见 CB-081（友盟/AppsFlyer 固定窗口） |
| 打印导出外链 | 🔴→✅ MA-002（P3，已修）：沉睡名单后端静默截 20 条——回填 22 个沉睡用户实证 KPI=22 但表格/CSV 导出/召回弹窗目标人数均 20 且无提示。修复=返回上限 20→1000（与 `UserRecallService.MAX_RECALL_USERS` 对齐）；复测 22/22 全量 |
| 文案口径 | 🔴→✅ MA-003（P3，已修）：召回弹窗「近 {{days}} 天沉睡名单」与实际固定 30-90 天窗口不符；目标人数改取全量 KPI 字段 `dormantUsers30d`，文案写死「30-90 天沉睡名单」 |
| 联动一致性 | ✓ 过期响应守卫（createLoadSeq）、召回按钮双权限门（`ops:user-analysis:view` AND `ops:coupon:create`）、后端 ACTIVE 券校验+FOR UPDATE+分布式锁 |

**发现**：3 缺陷（MA-001/002/003）均实跑复现→修复→钉测（UserBehaviorAnalyticsServiceTest 4/4 绿）→活栈回归（API 三档 + UI 22/22 + 弹窗文案）→证据落盘。测试数据（user_id 1990000001-22）已清理复原 0/0。

## 附带修复（R1 期间发现、非页面级）

- **Task 4**：`SKU_UPDATE` 审计 detail 只记 code/name/price ⇒ 下架无留痕。补 status/price/category 字段级前后值（`OpsCatalogAdminService.updateSku`）。
- **CB-080**：下架不清台账行（不级联，五家竞品一致）+ `delistedInventory` 残留死账 admin 可见可清。

## 发现汇总

| 级别 | 数量 | 编号 |
|------|------|------|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 2 | MA-001（用户分析口径，fixed）；大屏品类裸字典码（Task 5 修，散落在 CB-081/commit 355bb308，未单领号） |
| P3 | 4 | MA-002、MA-003（fixed）；大屏窄视口重叠（Task 6 修）；SKU_UPDATE 留痕缺口（Task 4 修） |

## 需用户拍板

- 无（本轮三缺陷均为口径/文案/展示层，无业务二选一）。

## 需用户配合（真机）

- 无（本轮全部本地可验证；NEAR_EXPIRY 行写操作待 dev 库出现临期 lot 或人工造数后补验）。

## 修复批次建议

- 全部当场修完并推送：`bfd3936c`（Task 4 + CB-080）→ `355bb308`（Task 5）→ `5a62f77e`+`0eb90b49`（Task 6 + admin-artifacts 门禁愈合）→ 用户分析批次（MA-001/002/003 + CB-081 + 门禁别名注册，见 ISSUES.md 修复记录）。

## 给下一轮的交接注记

- **待复核线索 #2**：设备报表「离线设备 5」vs 工作台「离线设备 3」同词两口径——先读两处取数函数体 + `device_info` 行数判口径，再定缺陷或加口径标注（勿直接对齐）。
- 大屏 1920px 严格档未单测（1600px 已回归）。
- 销售报表服务端排序仅 3 列（营收/退款/毛利），其余列排序为壳内单字段——换页/换维后壳内排序失效属已知设计，勿当新缺陷。
- R1 期间确认「入账前提=实跑复现」流程有效：设备报表两口径线索即因无法用现有数据区分而未入账。
