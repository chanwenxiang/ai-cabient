# 问题台账（MA-xxx 唯一编号源）

> 规则见 [README.md](README.md)。领号前先回读本表（编号全局递增，禁止撞号）。
> 入账前提：**已在当前 HEAD 实跑复现**（用户拍板：旧文档结论只当线索，复现通过才入账）。

## 索引表

| ID | 级 | 状态 | 端/页面 | 一句话 | 发现人/日期 | 关联 |
|----|----|------|---------|--------|-------------|------|
| MA-001 | P2 | fixed | admin `/user-analysis`（后端口径） | 30 天口径 KPI 随所选窗口漂移：days=7 时活跃/新增「30 天」显示 0（真值 2），days=90 时显示 24（真值 2） | Qoder R1 / 2026-10-31 | |
| MA-002 | P3 | fixed | admin `/user-analysis` | 沉睡名单静默截断 20 条：KPI=22 但表格/CSV 导出/召回弹窗「目标人数」均按 20 显示且无截断提示 | Qoder R1 / 2026-10-31 | |
| MA-003 | P3 | fixed | admin `/user-analysis`（召回弹窗） | 「目标人数（近 {{days}} 天沉睡名单）」文案与实际不符：沉睡窗口固定 30-90 天，与所选 days 无关 | Qoder R1 / 2026-10-31 | |

## 待复核线索（未入账，实跑复现后转 MA 编号）

### 线索 #1：供应商对账单页面疑似不可达（R4 复核）→ ✅ 已实证并收口（D28-B15，2026-10-29）

- **来源**（当前代码，非旧文档）：
  - `clients/admin-vue/src/router/index.ts` 有路由 `warehouse-reconciliation`（`/warehouse/reconciliation`，标题「供应商对账单」，meta 无 `perm`）；
  - `clients/admin-vue/src/config/menu.ts` 的 `BASE_NAV` **没有** `warehouse/reconciliation` 条目；
  - `router/index.ts` 的 `beforeEach`：`!nav && !metaPerm` → 一律重定向 `forbidden`（fail-closed），而 `findNavByPath('/warehouse/reconciliation')` 只查 `NAV_ITEMS`，查不到。
- **实跑证实（2026-10-29，Playwright + 超管 13900000001）**：直链 `/admin/warehouse/reconciliation` → `forbidden?from=...`，页面 0 个元素；且全站 grep 无任何入口链到该路由——整页死路，**P1 成立**。
- **额外实测发现**：即使补登记 NAV_ITEMS，挂后端码 `ops:procurement:list` 仍 403——`rbac/me/nav` 的 ACTIVE 菜单表（95 行）没有任何行用该码，`isNavMenuActive` 恒 false；须挂已在 ACTIVE 表里的码（兄弟子页口径 `ops:warehouse:list`）。
- **修复**：见 [admin-vue-debt-tracker.md 十五期 D28-B15](../engineering/admin-vue-debt-tracker.md)（F5 批次）：NAV_ITEMS 补登记 + 菜单码 `ops:warehouse:list`，页内「生成对账单」由 `canList`（=后端注解码 `ops:procurement:list`）收口；Playwright 复测页面可达。R4 无需再开 MA 条目。

## 明细区块（每条一节，追加在文件末尾）

<!-- 模板：
### MA-xxx 标题
- 级别/状态：P1 / open
- 端与页面：admin `/orders`（订单管理 → 退款弹窗）
- 发现人/日期/HEAD：xxx / 2026-10-xx / `<sha>`
- 环境：本地 docker（trade:18080，admin 产物 <sha>）/ 开发者工具 mp-weixin
- 复现步骤：1) … 2) … 3) …
- 期望 / 实际：… / …
- 证据：evidence/MA-xxx-1.png（截图/请求响应/控制台）
- 源码定位：`path/to/file.ts:123`
- 联动影响：consumer-mp `order-detail` 显示 …
- 关联：AU-xxx / D-xx（旧编号，仅线索）
- 修复记录：（fixed 时填：改动、验证方式、门禁结果）
-->

### MA-001 用户分析「30 天」口径 KPI 随所选窗口漂移（active30/new30 与 active7 同源）
- 级别/状态：P2 / open
- 端与页面：admin `/user-analysis`（根因在后端 UserBehaviorAnalyticsService）
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / `0eb90b49`
- 环境：本地 docker（trade:18080），运营超管账号
- 复现步骤：
  1) GET `/api/v2/ops/admin/growth/user-analysis?days=7`（库内两真实用户 last_order=2026-10-16/10-22，均在 30 天窗口内、7 天窗口外）→ `activeUsers7d=0, activeUsers30d=0, newUsers30d=0`，真值 active30=2、new30=2；
  2) `?days=90` 并回填 22 个 last_order=2026-08-20 的订单后探针 → `activeUsers30d=24`（真值 2，把 90 天窗口内活跃全计入「30 天」）；
  3) UI 近 7 天档 KPI 卡「活跃用户 0 / 30 天 0」。
- 期望 / 实际：`activeUsers30d`/`newUsers30d` 恒为近 30 天口径（标签写死「30 天」）/ 实际随 `days` 参数同窗：days<30 漏人（0），days>30 虚增（24）。
- 根因：`computeWindowMetrics` 只遍历 `windowOrders`（days 窗口），`active30++`/`active7++` 在同一循环对同一批用户计数 ⇒ active7≡active30≡「days 窗口活跃数」；`new30` 虽用 `since30` 比对首单，但迭代集仍是 days 窗口订单，days<30 时漏人。
- 证据：evidence/R01-useranalysis-active30-window-7d.png（近 7 天档 KPI=0/0）
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/UserBehaviorAnalyticsService.java:106-140`
- 联动影响：运营按「30 天活跃」判断大盘规模时，选 7 天档会读成 0（误判无活跃），选 90 天档读成 24（虚增 12 倍）。
- 修复记录：2026-10-31 fixed（commit 见 R1 批次）。`computeWindowMetrics` 改三窗口独立计数（7d/30d 固定 + days 窗口），订单查询取 `min(sinceWin, since30)` 并集；钉测 `summary_days7_active30AndNew30_stayFixed30dWindow`、`summary_days90_active30_notInflated_andDormantList_notTruncatedAt20`（UserBehaviorAnalyticsServiceTest，4/4 绿）；活栈容器重建后探针 days=7/30/90：active30 恒 2、new30 恒 2、orders 随窗口（0/14/14）。口径依据 CB-081。

### MA-002 沉睡名单静默截断 20 条，导出与召回弹窗按截断值显示
- 级别/状态：P3 / open
- 端与页面：admin `/user-analysis`（沉睡用户表格 + 导出沉睡名单 + 召回弹窗）
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / `0eb90b49`
- 环境：本地 docker（trade:18080）；回填 22 个 30-90 天沉睡用户（user_id 1990000001-22，已清理复原）
- 复现步骤：
  1) 回填 22 个 last_order=2026-08-20 的 PAID 订单+用户（`cabinet_order` 触发器 cutoff 2026-09-10 之前免 inventory 断言）；
  2) UI 沉睡 KPI=22，表格仅 20 行、无「仅显示前 20」提示；
  3) 点「导出沉睡名单」→ CSV 仅 20 行（前端 `s.dormantUsers` 已被截断）；
  4) 召回弹窗「目标人数 20 人」（真实 22）。
- 期望 / 实际：导出/展示口径与 KPI 一致（22 人），或明示截断 / 实际静默截 20。
- 根因：`UserBehaviorAnalyticsService.summary` 中 `if (dormant.size() > 20) dormant = dormant.subList(0, 20)`（dormantTotal 传全量，列表截 20，前端无从得知全量）。
- 证据：evidence/R01-useranalysis-dormant-truncated-30d.png、evidence/R01-useranalysis-recall-dialog-7d.png
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/UserBehaviorAnalyticsService.java:55-58`；前端导出 `clients/admin-vue/src/views/growth/UserAnalysisView.vue:257-269`
- 联动影响：召回不受影响（后端 `UserRecallService` 自行取全量、上限 1000），但运营按弹窗「20 人」核对发放数会对不上；导出名单不完整。
- 修复记录：2026-10-31 fixed。截断 20→`MAX_DORMANT_RETURNED=1000`（与 `UserRecallService.MAX_RECALL_USERS` 对齐）；回填 22 个沉睡用户复测：KPI=22、表格 22 行、导出同源。UI 回归截图 evidence/R01-useranalysis-fixed-dialog-22-full.png。测试数据已清理（0/0）。

### MA-003 召回弹窗「近 {{days}} 天沉睡名单」文案与实际固定 30-90 天窗口不符
- 级别/状态：P3 / open
- 端与页面：admin `/user-analysis`（一键召回弹窗）
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / `0eb90b49`
- 环境：同 MA-002
- 复现步骤：选「近 7 天」→ 沉睡表格仍有 22 人（固定窗口）→ 点「一键召回」→ 弹窗显示「20 人（近 7 天沉睡名单，最多 1000 人）」。
- 期望 / 实际：文案「30-90 天沉睡名单」（沉睡窗口固定，`UserBehaviorAnalyticsService` 用固定 dormantCutoff/dormantFloor，与 days 无关；后端召回同源）/ 实际按所选 days 动态拼「近 7 天」，误导运营以为召回目标随窗口变化。
- 证据：evidence/R01-useranalysis-recall-dialog-7d.png
- 源码定位：`clients/admin-vue/src/views/growth/UserAnalysisView.vue:191`
- 联动影响：纯文案；行为本身一致（前后端都用同一固定窗口名单）。
- 修复记录：2026-10-31 fixed。`UserAnalysisView.vue` 弹窗改「{{ s?.dormantUsers30d ?? 0 }} 人（30-90 天沉睡名单，最多 1000 人）」——人数取全量 KPI 字段、窗口文案写死真实口径；复测弹窗显示「22 人（30-90 天沉睡名单）」。
### 线索 #2：设备报表 vs 工作台「离线设备」同词两口径（R1，未复现未领号）

- **来源**（R01 会话，2026-10-31）：设备报表 `/reports` 显「离线设备 5」（全部设备），工作台 `/admin` 显「离线设备 3」（疑 deployed/投放柜口径）。同词不同数，运营无法判断哪个是真。
- **未复现原因**：dev 库设备行会随环境变动，两处取数函数体未逐行比对，按入账前提（实跑复现才入账）暂不领号。
- **下一步**：读两处取数函数体 + `device_info` 行数/lifecycle 分布，判定是缺陷还是口径差；若为口径差，按「汇总行=全集、子集列表」惯例加口径标注而非对齐（参考记忆规则：negation/口径类先查权威模型）。
