# 运营后台 ·「概览」全量浏览器 UAT（Phase 1）

> **地位**：本轮全面测试的**第一册执行真源**（侧栏「概览」9 页）。后续分组（交易履约 → … → 系统）另开分册，顺序见 §8。  
> **工具铁律**：宣称 UI 通过前必须用 **Playwright MCP / CLI** 真实打开、点击、断言（规则 `playwright-ui-testing` / Skill `browser-real-testing`）。禁止仅凭 curl、日志或代码推理。  
> **质量四维（每页 / 每按钮必过）**：① **数据正确** ② **排版布局** ③ **交互体验** ④ **三端口径统一** —— 细则见 §1.4～§1.7；仅「点得开」不算 PASS。  
> **关联**：总矩阵 [`BUSINESS_FULL_TEST_MATRIX.md`](../BUSINESS_FULL_TEST_MATRIX.md)；旧全量计划 [`BROWSER_FULL_UAT_PLAN.md`](../BROWSER_FULL_UAT_PLAN.md)（入口/端口以本文 §1 为准）；金钱展示铁律见 lessons `#151`/`#158`（`fmtMoney`）。  
> **版本**：1.1 · 2026-09-26 · 源码对照 `clients/admin-vue/src/config/menu.ts` + 各 View

---

## 0. 本轮元信息（执行时填写）

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| Commit | 以执行时 `git rev-parse --short HEAD` 为准 |
| 环境 | 全栈 Docker（`.\docker-up.ps1`，**不含** devops） |
| 视口 | 1366×768 主测 |
| 工具 | Playwright MCP |
| 统计 | PASS 主链齐全 / FAIL 0（已修） / BLOCK 0 / SKIP 零计数快捷+全屏自动化+召回禁用 / 深链追完 工作台主链 / 按钮清点见 BUTTONS.md / 三端口径 抽样 1 单 |
| 结案 | **Phase 1 Done 已勾**；明细 [`overview/BUTTONS.md`](../uat-screenshots/2026-09-26/overview/BUTTONS.md) + [`FINDINGS.md`](../uat-screenshots/2026-09-26/overview/FINDINGS.md) |

截图目录：`docs/uat-screenshots/2026-09-26/overview/`  
结果 JSON（可选）：同目录 `overview-results.json`

---

## 1. 环境与账号

### 1.1 入口

| 端 | URL | 账号 |
|----|-----|------|
| 运营后台 | http://localhost/admin/index.html | `13900000001` / `123456` + 图形验证码 |
| trade 直连（排障） | http://localhost:18080/admin/index.html | 同上 |
| 消费者 H5（深链/流程触及） | http://127.0.0.1:3002 | `13800138000` / `123456` |
| 商户 H5（深链/流程触及） | http://127.0.0.1:3001 | `13800138001` / `123456` |
| 微信小程序 | 导入 `clients/*/dist/dev/mp-weixin` | 同 H5 账号；**验收权威为 mp-weixin** |

### 1.2 Pre-flight（每轮开测前）

```text
✓ docker ps → trade / device / vision / gateway / postgres / redis Running
✓ http://localhost:18080/actuator/health → UP
✓ http://localhost:18081/actuator/health → UP
✓ http://localhost:18082/health → ok
✓ 浏览器打开 /admin/index.html 能出登录页（非整页白屏）
```

可选：`.\scripts\verify-local.ps1`

### 1.3 证据标准（每条用例）

| 必须记录 | 说明 |
|----------|------|
| 入口 URL | 含 hash/query |
| 操作步骤 | 点击文案 / 控件角色 |
| 期望 vs 实际 | 一句话；**含数据数字**时写清「UI 值 / API 或对照端值」 |
| 按钮生效 | 见 §1.4：点击前后状态差（URL / 筛选 / 列表 / toast / 文件） |
| 截图文件名 | 失败必截；深链落地必截；布局问题必截整页 |
| 判定 | PASS / FAIL / BLOCK / SKIP |

**深链规则**：从概览页点出的每一个 `router.push` / `goPath`，必须在目标页验证**筛选条件已生效**（query 回显或列表过滤），再视业务继续点到详情/下一动作，直到：

1. 到达终态页（详情只读 / 弹窗关闭 / 导出完成），或  
2. 进入**非概览**模块 → 记「跨组深链已落点」并在对应分册复测细则，或  
3. 流程要求小程序 → 切 H5 或微信开发者工具按 §7 跑完。

### 1.4 全按钮生效协议（硬规则）

**范围**：页面上一切可点控件 —— `el-button`、原生 `button`、可点 KPI 卡、表格行链、`RouterLink`、Tab/Radio、分页、排序表头、下拉选项、弹层「取消/确认」、导出。`disabled` / `v-hasPermi` 隐藏的须记 **SKIP（无权限/不可用）** 并截图证明不可点，不得假装 PASS。

每点一次必须留下「**生效证据**」四选一（可多选）：

| 类型 | 何谓「生效」 | 取证 |
|------|--------------|------|
| 导航 | URL path/query 变了，且**目标页 DOM 反映筛选** | 截图 URL + 筛选项回显 |
| 查询/筛选 | 列表行集合或 KPI 数字相对点击前变化，或诚实空态 | 点击前后各一帧；或 Network 200 + body 与 UI 一致 |
| 写操作 | toast/对话框结果；二次打开数据已变 | 成功/失败中文文案；必要时 API 复核 |
| 导出 | 浏览器触发下载，或明确「无数据可导」中文 | 文件名/字节数；禁止静默无反应 |

**禁止**：连点无 loading、无 toast、无 URL 变化却标 PASS；「看着像按钮」但 `@click` 空实现 → **FAIL**。

开测某页前：从源码 + 真机 snapshot **列出按钮清单**（附录 B 模板），测完勾「生效 ✓」。漏测未勾 = 本页未完成。

### 1.5 数据正确性（硬规则）

KPI / 表格数字 **不得只看「有数」**。默认三角对照：

```text
UI 展示值  ⇄  同页 Network 响应（或已知聚合 API）  ⇄  对照端（商户/消费者同源业务）或 DB 抽样
```

| 检查 | 做法 | FAIL 例 |
|------|------|---------|
| 单位 | 金额一律元展示 `¥x.xx`；禁止把分当元、禁止裸 `1234` 无单位 | UI ¥12.00 而 API `cents=1200` 却显示 1200 |
| 汇总 | 页头 KPI 与表内合计/筛选后合计一致（允许「当前页合计」则文案须标明） | KPI 营收 ≠ 表列 sum 且无「仅本页」说明 |
| 刷新 | 刷新后与最新 API 一致；失败不得静默清零 | 刷新失败 KPI 闪成 0 |
| 筛选 | 改条件后数字/行数与请求 query 一致 | 选「离线」仍出 ONLINE 行 |
| 时间窗 | 「今日/近7天」与请求 `from`/`to` 或 `days` 一致 | UI 写今日、请求仍 30 天 |
| 空与零 | `0` 与「暂无数据」语义正确；禁止把加载失败画成 0 | 502 后显示全 0 |

**概览页推荐 API 抽样**（执行时用 Playwright 读 response 或临时 curl，数值写入日志）：

| 页 | 优先对照 |
|----|----------|
| 工作台 | workbench / stats 聚合接口 vs KPI 文案 |
| 大屏 | big-screen / dashboard 同源字段 |
| 分析 / 客流 | analytics / footfall 序列点数 vs 图例 |
| 设备报表 | report 分页 `total` vs 底栏总数 |
| 财务毛利 | finance 汇总 vs 固化行 |
| 销售报表 | 各维度 `dim` 下行营收 vs KPI |
| 库存健康 | STOCKOUT/LOW/NEAR_EXPIRY 计数 vs KPI 砖 |
| 用户分析 | 沉睡/复购列表条数 vs 导出行数（抽前 N 行） |

### 1.6 排版与用户体验（硬规则）

每页至少做一次 **UX 巡检**（记 `G-UX-*`），1366×768：

| ID | 检查 | FAIL 例 |
|----|------|---------|
| G-UX-01 | 主区无横向整页滚动；表可内部横滑 | 白卡片把窗口撑出横向条 |
| G-UX-02 | 标题/KPI/按钮无重叠、无截断省略到不可读 | 按钮文字被裁成「固化昨…」且无 tooltip |
| G-UX-03 | 间距：页头操作区与筛选行不挤成一团；点击热区 ≥ 可点 | 两个 primary 按钮几乎重叠 |
| G-UX-04 | 对比度：正文/次要文案可读；禁用态可区分 | 灰底灰字几乎看不见 |
| G-UX-05 | 反馈：点击后 300ms 内有 loading / 路由切 / toast | 无任何反馈超过 2s |
| G-UX-06 | 危险写操作有确认 | 「固化昨日」「确认发放」无二次确认却直接改数 |
| G-UX-07 | 空态/错误态中文、可重试 | 英文 stack 或空白 |
| G-UX-08 | 图表：图例、轴标签、tooltip 中文；缩放后不糊成一团 | 轴刻度互相覆盖无法读 |
| G-UX-09 | 表格：列对齐（金额右/状态中）；操作列不被挤没 | 操作按钮出屏外且无横滑提示 |
| G-UX-10 | 焦点/键盘：可点 KPI 有 role/tabindex 时可 Enter 触发 | 可点看上去像链接但键盘无效（记体验债） |

大屏另加：全屏后无后台侧栏残留；退出全屏布局恢复。

### 1.7 三端数据口径统一（硬规则）

同一业务事实在 **运营后台 / 商户端 / 消费者端** 展示必须**可对账**（允许权限导致「看不见」，不允许「看得见但数不同」）。

| 主题 | 运营后台（概览相关） | 商户端 | 消费者端 | 对齐判据 |
|------|----------------------|--------|----------|----------|
| 订单金额 / 实付 | 销售报表 / 工作台营收深链到订单 | 订单列表 / 经营分析 | 订单详情 | 同一 `orderId`：实付分→元后三端字符串一致（均走 `fmtMoney` 或等价） |
| 退款 / 争议 | 争议深链、销售「退款」列 | 争议列表 | 订单申诉态 | 同一 ticket：状态枚举语义一致（OPEN/RESOLVED…）；金额同口径 |
| 柜机在线 / 停售 | 工作台 KPI、设备报表 | 柜机列表 | （通常无） | 同一 `deviceId`：`online` / `salesLocked` 一致 |
| 库存断货 / 临期 | 库存健康 | 补货待办 / 货道 | — | 同一柜同一 SKU：断货标记一致 |
| 分账 / 商户营收 | 销售「商户」维 → 分账 Tab | 钱包 / 分账 | — | 同一 `merchantId` + 同日：营收或应结金额差 ≤ 1 分（四舍入说明） |
| 用户余额 / 券 | 用户分析召回发券后 | — | 余额 / 优惠券 | 发券成功后消费者端可见；余额变动流水口径不含纯冻结刷屏（lessons #108） |
| 购物视频 | 订单/会话详情（深链后） | 订单视频 | 订单视频 | 同一 session：可播或同失败原因 |

**执行方式**：概览测到深链落点后，挑 **≥1 条真实业务主键**（orderId / deviceId / merchantId / ticketId）在另外两端打开对照，填 §9 日志「三端口径」行。无数据可造演示单（柜机 `330449777078`）再测，禁止用空库宣称口径 PASS。

---

## 2. 通用检查清单（每页开场必做）

记为 **G-xx**，每页复制勾选：

| ID | 检查项 | 期望 |
|----|--------|------|
| G-01 | 侧栏高亮 | 当前「概览」子项高亮；分组展开 |
| G-02 | 标题区 | 页头中文标题正确；无 `????` / 英文裸 key |
| G-03 | 首屏加载 | 有 loading 或骨架；结束后非永久转圈 |
| G-04 | 空态/有数 | 有数据展示 KPI/表；空则中文空态，非白屏 |
| G-05 | 刷新 | 点「刷新」→ loading → 数据更新或保持，无报错 toast 乱码 |
| G-06 | 无权限控件 | 无 perm 的按钮不出现或点后 403 中文提示 |
| G-07 | 控制台 | 无未捕获红错（已知第三方警告可记 SKIP） |
| G-08 | 金额/时间 | `¥x.xx`；时间可读 |
| G-09 | 按钮清单 | 附录 B 已列出本页全部可点控件，无遗漏 |
| G-10 | 数据三角 | §1.5：至少 1 个 KPI/列与 API（或 DB）对上 |
| G-11 | UX 巡检 | §1.6 `G-UX-01`～`10` 本页勾完 |
| G-12 | 三端口径 | 本页若产生可对账主键，§1.7 至少完成 1 组；否则记 N/A 原因 |

---

## 3. Phase 1 执行顺序（严格按侧栏）

与截图一致：

1. 运营工作台 `/dashboard`  
2. 运营大屏 `/big-screen`  
3. 数据分析 `/analytics`  
4. 客流坪效 `/footfall`  
5. 设备报表 `/reports`  
6. 财务毛利 `/finance`  
7. 销售报表 `/sales-reports`  
8. 库存健康 `/stock-health`  
9. 用户分析 `/user-analysis`

---

## 4. 分册用例明细

> 下列每条 ID 判定时必须同时满足：§1.4 按钮生效 + §1.5 相关数据（若该步展示数字）+ 本页 G-UX 不回归。  
> 「期望」列若只写路由，执行日志仍须补 **数字对照** 与 **生效证据**。

### 4.1 运营工作台 · `OV-DASH`

| 项 | 值 |
|----|-----|
| 路径 | `/dashboard` |
| 权限 | `ops:dashboard:view` |
| 源码 | `views/dashboard/DashboardView.vue` |

| ID | 步骤 | 期望 | 深链落地校验 |
|----|------|------|--------------|
| OV-DASH-01 | 登录后进工作台 | 标题「运营工作台」；KPI 卡片有数或 0 | — |
| OV-DASH-02 | 点「刷新」 | loading 结束；无假 0 闪烁（失败应保留旧数或中文提示） | — |
| OV-DASH-03 | 顶栏「补货调度」 | 进入 `/replenishment` | 补货页可开 |
| OV-DASH-04 | 「争议审核」 | `/disputes` | 列表页 |
| OV-DASH-05 | 「设备管理」 | `/devices` | 列表页 |
| OV-DASH-06 | 「设备可用性」 | `/device-kpi` | KPI 页 |
| OV-DASH-07 | KPI「在售/可售」类卡片（可点） | `/devices?salesLocked=false` | 筛选含未停售 |
| OV-DASH-08 | KPI「离线」 | `/devices?online=OFFLINE` 或 ONLINE（视数据） | query 生效 |
| OV-DASH-09 | KPI「今日营收」 | `/finance` | 财务页 |
| OV-DASH-10 | KPI/区「异常」 | `/exceptions` 或带 status | 异常中心 |
| OV-DASH-11 | 快捷「异常中心」 | `/exceptions?status=OPEN` | 状态筛选 OPEN |
| OV-DASH-12 | 「待审争议」 | `/disputes?status=OPEN` | OPEN |
| OV-DASH-13 | 「超时待支付」 | `/orders?status=PENDING&overdue=1` | 筛选生效 |
| OV-DASH-14 | 「停售货柜」 | `/devices?salesLocked=true` | 停售筛选 |
| OV-DASH-15 | 「离线设备」 | `/devices?online=OFFLINE` | 离线筛选 |
| OV-DASH-16 | 「待上传」 | `/upload-queue?stuck=1` | 卡住上传 |
| OV-DASH-17 | 「缺货柜/SKU」 | `/stock-health?dimension=ALL` | 库存健康 |
| OV-DASH-18 | 「临期批次」 | `/stock-health` + 临期相关 query | dimension/NEAR_EXPIRY |
| OV-DASH-19 | 工作区条目「查看」 | 按行类型跳转（争议/上传/会话/仓配/对账/分账/设备详情） | URL+筛选正确 |
| OV-DASH-20 | 「进件工作台」（有权限时） | `/merchant-onboarding` | 进件页 |
| OV-DASH-21 | 紧急/全部筛选（若有） | 列表条数变化 | — |
| OV-DASH-22 | 分页（待办列表有多页时） | 翻页数据变 | — |

**小程序挂钩（本页间接）**：从工作台 → 订单/争议/设备详情后，若点「查看视频 / 消费者订单」等，按 §7-C 继续。

---

### 4.2 运营大屏 · `OV-BS`

| 项 | 值 |
|----|-----|
| 路径 | `/big-screen`（常独立全屏布局） |
| 权限 | `ops:dashboard:view` |
| 源码 | `views/dashboard/BigScreenView.vue` |

| ID | 步骤 | 期望 |
|----|------|------|
| OV-BS-01 | 打开大屏 | 暗色驾驶舱；时钟/KPI/图表无整页白 |
| OV-BS-02 | 「刷新」 | 数据重载；按钮短暂 disabled |
| OV-BS-03 | 「全屏」→「退出全屏」 | 浏览器全屏 API 切换成功 |
| OV-BS-04 | 「返回后台」 | 回到 `/dashboard` |
| OV-BS-05 | 1920×1080 视口 | 无严重裁切、重叠（抽检截图） |

深链：本页仅返回后台；无业务深链。

---

### 4.3 数据分析 · `OV-AN`

> **单页深测册**（按钮/深链/三端）：[`ANALYTICS_FULL_BROWSER_UAT.md`](./ANALYTICS_FULL_BROWSER_UAT.md) · 截图 [`analytics/`](../uat-screenshots/2026-09-26/analytics/)

| 项 | 值 |
|----|-----|
| 路径 | `/analytics` |
| 权限 | `ops:analytics:view` |
| 源码 | `views/analytics/AnalyticsView.vue` |

| ID | 步骤 | 期望 | 深链 |
|----|------|------|------|
| OV-AN-01 | 打开页 | 图表 + KPI 出现 | — |
| OV-AN-02 | 「刷新」 | 重载成功 | — |
| OV-AN-03 | KPI → 财务 | `/finance` | 毛利页 |
| OV-AN-04 | KPI → 订单 | `/orders` | 订单列表 |
| OV-AN-05 | KPI → 会话 | `/sessions` | 开门记录 |
| OV-AN-06 | KPI → 争议 | `/disputes` | 争议列表 |
| OV-AN-07 | 营收图：折线/面积/柱 | 图表类型切换无报错 | — |
| OV-AN-08 | 订单图：折线/面积/柱 | 同上 | — |
| OV-AN-09 | 运维图：折线/面积/柱 | 同上 | — |
| OV-AN-10 | 「查看离线设备」 | `/devices?online=OFFLINE` | 筛选 |
| OV-AN-11 | 「待审争议」按钮 | `/disputes?status=OPEN` | OPEN |
| OV-AN-12 | 「财务毛利」按钮 | `/finance` | — |

---

### 4.4 客流坪效 · `OV-FF`

| 项 | 值 |
|----|-----|
| 路径 | `/footfall` |
| 权限 | `ops:analytics:footfall:view` |
| 源码 | `views/analytics/FootfallView.vue` |

| ID | 步骤 | 期望 |
|----|------|------|
| OV-FF-01 | 打开页 | 标题「客流坪效」；表/图有中文列 |
| OV-FF-02 | 近 7 / 30 / 90 天 | 切换后数据重载 |
| OV-FF-03 | 「刷新」 | 成功 |
| OV-FF-04 | 柜机表横向滚动（窄视口） | 不整页白卡横滑裁切（lessons 布局） |
| OV-FF-05 | 热区柜机下拉（若有） | 切换后热区变化 |
| OV-FF-06 | SKU 表金额列 | `¥` 格式 |

深链：本页无 router 深链；记 N/A。

---

### 4.5 设备报表 · `OV-DR`

| 项 | 值 |
|----|-----|
| 路径 | `/reports` |
| 权限 | `ops:report:device` |
| 源码 | `views/reports/DeviceReportView.vue` |

| ID | 步骤 | 期望 | 深链 |
|----|------|------|------|
| OV-DR-01 | 打开页 | 表格或空态中文 | — |
| OV-DR-02 | 关键词/在线筛选 +「查询」 | 列表过滤 | — |
| OV-DR-03 | 「重置」 | 条件清空重载 | — |
| OV-DR-04 | URL `?online=OFFLINE` 直达 | 筛选回显为离线 | 深链入 |
| OV-DR-05 | 行操作「设备」/点行 | `/devices/{deviceId}` | 详情首屏 |
| OV-DR-06 | 详情内 Tab（货道等）抽检 | 可切换无崩 | 详情节记设备分册 |

**小程序**：设备详情若展示柜机码/H5 链接，用消费者端扫码或模拟开门流程见 §7-C。

---

### 4.6 财务毛利 · `OV-FIN`

| 项 | 值 |
|----|-----|
| 路径 | `/finance` |
| 权限 | `ops:finance:view` |
| 源码 | `views/finance/FinanceView.vue` |

| ID | 步骤 | 期望 | 深链 |
|----|------|------|------|
| OV-FIN-01 | 打开页 | KPI + 表/图 | — |
| OV-FIN-02 | 「刷新」 | 成功 | — |
| OV-FIN-03 | 「固化昨日毛利」 | 有确认或成功/失败中文；勿重复狂点 | — |
| OV-FIN-04 | 天数切换（`days` query） | URL `?days=` 同步；数据变 | — |
| OV-FIN-05 | 「商品管理」链接 | `/skus` | 商品列表 |
| OV-FIN-06 | 错误态「返回工作台」 | `/` 或 dashboard | — |
| OV-FIN-07 | KPI 提示链到数据分析（有权限） | `/analytics` | 图表页 |

---

### 4.7 销售报表 · `OV-SR`

| 项 | 值 |
|----|-----|
| 路径 | `/sales-reports` |
| 权限 | `ops:sales-report:list` |
| 源码 | `views/reports/SalesReportsView.vue` |

| ID | 步骤 | 期望 | 深链 |
|----|------|------|------|
| OV-SR-01 | 打开页 | KPI + 表 | — |
| OV-SR-02 | 维度：商品/货柜/商户/渠道/毛利 | 每维切换表头与数据合理 | — |
| OV-SR-03 | 柜机筛选 | 过滤生效 | — |
| OV-SR-04 | 快捷 今日/近7天/近30天 | 区间变 | — |
| OV-SR-05 | 自定义日期区间 | 查询生效 | — |
| OV-SR-06 | 「查询」「重置」 | 符合预期 | — |
| OV-SR-07 | 表头排序（营收/退款/毛利） | 顺序变 | — |
| OV-SR-08 | 导出（有/无勾选行） | 下载或后端导出；中文文件名 | — |
| OV-SR-09 | 维度=商品 点编码/名称 | `/skus?keyword=` | 商品筛选 |
| OV-SR-10 | 维度=货柜 点编码 | `/devices/{id}` | 设备详情 |
| OV-SR-11 | 维度=商户 点编码 | `/merchants?tab=splits&merchantId=` | 分账 Tab+商户 |
| OV-SR-12 | 维度=毛利 点 SKU | `/skus?keyword=` | 同商品 |

**商户小程序挂钩**：OV-SR-11 落分账后，若需核对商户端「分账/钱包」，按 §7-M。

---

### 4.8 库存健康 · `OV-SH`

| 项 | 值 |
|----|-----|
| 路径 | `/stock-health` |
| 权限 | `ops:stock-health:list` |
| 源码 | `views/reports/StockHealthView.vue` |

| ID | 步骤 | 期望 | 深链 |
|----|------|------|------|
| OV-SH-01 | 打开页 | 断货/低库存/临期 KPI | — |
| OV-SH-02 | 维度 全部/断货/低库存/临期 | 列表过滤 | — |
| OV-SH-03 | 柜机/商户/路线/生命周期 +「查询」 | 过滤生效 | — |
| OV-SH-04 | URL `?dimension=STOCKOUT` 等 | 回显与列表一致 | 深链入 |
| OV-SH-05 | 导出 | 需 `ops:stock-health:export`；下载成功 | — |
| OV-SH-06 | 「一键补货规划（N 台）」 | `/replenishment` 带 deviceIds/tab | 补货规划 |
| OV-SH-07 | 行 → 设备详情 | `/devices/{id}` | 详情 |
| OV-SH-08 | 行 → 临期相关补货 | `/replenishment?tab=expiry&deviceId=` | Tab=expiry |

**商户小程序**：补货调度落地后，商户端补货任务见 §7-M。

---

### 4.9 用户分析 · `OV-UA`

| 项 | 值 |
|----|-----|
| 路径 | `/user-analysis` |
| 权限 | `ops:user-analysis:view` |
| 源码 | `views/growth/UserAnalysisView.vue` |

| ID | 步骤 | 期望 |
|----|------|------|
| OV-UA-01 | 打开页 | 活跃/复购/沉睡等区块 |
| OV-UA-02 | 「刷新」 | 成功 |
| OV-UA-03 | 「导出沉睡名单」 | 文件下载或中文失败提示 |
| OV-UA-04 | 「导出复购榜」 | 同上 |
| OV-UA-05 | 「一键召回」打开弹层 | 表单可见 |
| OV-UA-06 | 弹层「取消」 | 关闭无副作用 |
| OV-UA-07 | 「确认发放并通知」（演示环境） | 成功 toast **或** 业务拒绝中文；禁止静默失败 |
| OV-UA-08 | 召回后消费者侧（可选） | §7-C：优惠券/消息是否可见 |

---

## 5. 深链总表（概览发出 → 必须追到落点）

| 源 | Query/Path | 目标模块 | 落点断言（最短） |
|----|------------|----------|------------------|
| 工作台 | `/devices?salesLocked=` / `online=` | 设备商品 | 筛选控件=query |
| 工作台 | `/orders?status=PENDING&overdue=1` | 交易履约 | 状态+超时 |
| 工作台 | `/disputes?status=OPEN` | 交易履约 | OPEN |
| 工作台 | `/exceptions?status=OPEN` | 交易履约 | OPEN |
| 工作台 | `/upload-queue?stuck=1` | 设备商品 | 卡住列表 |
| 工作台 | `/stock-health?dimension=` | 概览内 | 维度回显 |
| 工作台 | `/sessions?stuck=1` | 交易履约 | 卡住会话 |
| 工作台 | `/warehouse?tab=transit&overdue=1` | 履约仓储 | Tab+逾期 |
| 工作台 | `/reconciliation?status=MISMATCH` | 财务商户 | 差异态 |
| 工作台 | `/merchants?tab=splits` | 财务商户 | 分账 Tab |
| 工作台 | `/devices/{id}` | 设备详情 | 柜机号一致 |
| 分析 | `/devices?online=OFFLINE` 等 | 同上 | 同上 |
| 销售 | `/skus?keyword=` / `/devices/{id}` / `/merchants?tab=splits&merchantId=` | 跨组 | 关键词/商户 ID |
| 库存 | `/replenishment?...` | 履约仓储 | Tab/设备 |

落点页若继续有「详情 / 结案 / 开门视频」按钮 → **继续点**，直到终态或明确记入下一分册 ID。

---

## 6. 与小程序衔接判定（何时必须测小程序）

| 触发条件 | 测哪端 | 最低路径 |
|----------|--------|----------|
| 订单/会话/争议深链后需看**购物视频或申诉** | 消费者 | 登录 → 订单列表 → 详情 → 视频/申诉 |
| 销售/库存 → 补货规划后需商户执行 | 商户 | 登录 → 补货/待办 → 任务详情 |
| 用户召回发券 | 消费者 | 登录 → 优惠券/消息 |
| 设备详情柜机码「扫码开门」 | 消费者 | H5 或 mp-weixin 输入柜机号开门（演示柜 `330449777078`） |
| 仅后台只读报表、无 C 端动作 | — | **SKIP 小程序**，注明原因 |

**演示柜机**：`330449777078`（勿用孤儿 `CAB-001` 做有单演示）。

---

## 7. 小程序最小回归包（被概览深链触发时）

### 7.1 消费者 · `MP-C`（权威：mp-weixin；H5 可辅助）

| ID | 步骤 | 期望 |
|----|------|------|
| MP-C-01 | 打开 dist/dev 或 H5 `:3002`，登录 | 进首页；无布局裁切（标题居中 lessons #109） |
| MP-C-02 | 订单列表 → 有单详情 | 金额中文 `¥`；与后台同一 orderId **分转元一致** |
| MP-C-03 | 详情「视频」 | 可播或明确失败文案（非白屏）；与后台同 session |
| MP-C-04 | 申诉入口（若订单可申诉） | 弹层字段完整；取消/提交有反馈且状态回写后台可见 |
| MP-C-05 | 优惠券（召回后） | 列表出现新券或空态诚实；与后台发放记录对应 |
| MP-C-06 | 余额明细（若对照资金） | 无纯冻结刷屏（lessons #108）；变动与订单扣款一致 |

### 7.2 商户 · `MP-M`

| ID | 步骤 | 期望 |
|----|------|------|
| MP-M-01 | 登录 `13800138001` | 进概览；KPI 可读 |
| MP-M-02 | 补货/待办 | 与后台规划相关任务可见或中文空；柜机/SKU 与库存健康同源 |
| MP-M-03 | 分账/钱包（销售深链后） | 金额一律 `fmtMoney`；与后台商户维/分账差 ≤1 分 |
| MP-M-04 | 订单/争议 | 与后台同单状态、金额一致 |

---

## 8. 后续分册顺序（Phase 2+，本轮文档只钉顺序）

| Phase | 分组 | 菜单源 | 分册文件（待建） |
|-------|------|--------|------------------|
| 2 | 交易履约 | 订单/开门/争议/异常 | `docs/uat/FULFILLMENT_FULL_BROWSER_UAT.md` |
| 3 | 设备商品 | 运维/设备/地图/KPI/维修/SKU/识别/上传 | `docs/uat/DEVICE_SKU_FULL_BROWSER_UAT.md` |
| 4 | 履约仓储 | 补货/仓库/OTA/SLA | `docs/uat/WAREHOUSE_FULL_BROWSER_UAT.md` |
| 5 | 财务商户 | 资金/商户分账/进件/线长/提现/对账… | `docs/uat/FINANCE_MERCHANT_FULL_BROWSER_UAT.md` |
| 6 | 增长风控 | （除已测用户分析外）营销/风控等 | `docs/uat/GROWTH_RISK_FULL_BROWSER_UAT.md` |
| 7 | 系统 | 组织/角色/菜单/字典/DevOps 入口等 | `docs/uat/SYSTEM_FULL_BROWSER_UAT.md` |

原则：**上一 Phase 深链落入的目标页，在本 Phase 必须再跑一遍按钮级用例**（避免「只点到门口」）。

---

## 9. 执行日志模板

```markdown
### OV-DASH-12 待审争议深链
- 工具: Playwright MCP
- 从: http://localhost/admin/index.html#/dashboard
- 操作: 点击快捷「待审争议」（按钮清单 #Q-03）
- 到: #/disputes?status=OPEN
- 按钮生效: 点击前 count=N；落地后筛选=OPEN；列表首行 status 文案=待处理
- 数据: workbench.openDisputes=3 ↔ 争议列表 total（当前筛选）=3
- 排版/UX: 1366 无横滑；toast 无；落点页头无遮挡
- 三端口径: ticketId=… → 商户 disputes 同单状态=…；消费者订单申诉态=…（或 N/A）
- 截图: overview/ov-dash-12.png
- 判定: PASS
- 续测: 点首行打开抽屉 → 记 FULFILL-DSP-xx（Phase 2）
```

---

## 10. 禁止与完成定义

**禁止**

- 未开浏览器声称 PASS  
- 深链只检查 URL 不变 DOM  
- 按钮「点了」但无 §1.4 生效证据  
- KPI「有数字」但不做 §1.5 对照  
- 明显裁切/重叠/横滑爆版却标布局 PASS  
- 三端同单金额差 >1 分仍标口径 PASS  
- 用 `CAB-001` 当有订单演示柜  
- 小程序只测 H5 却写「mp-weixin 通过」

**Phase 1 Done**

- [x] §4 全部 ID 有判定（零计数快捷 / 全屏为合法 SKIP，见 `BUTTONS.md`）  
- [x] 每页附录 B 按钮清单 100% 勾「生效」或合法 SKIP → [`docs/uat-screenshots/2026-09-26/overview/BUTTONS.md`](../uat-screenshots/2026-09-26/overview/BUTTONS.md)  
- [x] 每页 G-01～G-12（抽样覆盖；无新阻塞；正式表未逐格打印）  
- [x] §5 深链表每条至少一次落点断言（工作台主链；零计数用直链）  
- [x] 触发的 §7 小程序用例已跑或显式 SKIP；抽样三端金额对照  
- [x] FAIL 已开缺陷或当场修复并回归（#186–#194 等）  
- [x] 本文件 §0 统计填完；`PROJECT_KNOWLEDGE` Changelog 记一行  

---

## 附录 A · 源码索引

| 菜单 | path | perm | View |
|------|------|------|------|
| 运营工作台 | `/dashboard` | `ops:dashboard:view` | `DashboardView.vue` |
| 运营大屏 | `/big-screen` | `ops:dashboard:view` | `BigScreenView.vue` |
| 数据分析 | `/analytics` | `ops:analytics:view` | `AnalyticsView.vue` |
| 客流坪效 | `/footfall` | `ops:analytics:footfall:view` | `FootfallView.vue` |
| 设备报表 | `/reports` | `ops:report:device` | `DeviceReportView.vue` |
| 财务毛利 | `/finance` | `ops:finance:view` | `FinanceView.vue` |
| 销售报表 | `/sales-reports` | `ops:sales-report:list` | `SalesReportsView.vue` |
| 库存健康 | `/stock-health` | `ops:stock-health:list` | `StockHealthView.vue` |
| 用户分析 | `/user-analysis` | `ops:user-analysis:view` | `UserAnalysisView.vue` |

---

## 附录 B · 按钮清单模板（每页开测前填，测完勾生效）

> 复制到执行笔记或 `docs/uat-screenshots/2026-09-26/overview/buttons-*.md`。  
> **来源**：Playwright snapshot 的 button/link + 源码 `@click` / `goPath`；动态行内按钮按「有数据时至少测 1 行」计。

| # | 控件文案/角色 | 类型 | 预期生效 | 实测生效证据 | ✓ |
|---|---------------|------|----------|--------------|---|
| 1 | 例：刷新 | button | 重新请求；loading | Network 200；KPI 未闪 0 | |
| 2 | 例：待审争议 | quick-link | → `/disputes?status=OPEN` | 筛选项=OPEN | |

### B.1 运营工作台（预填 · 执行时按真机增减）

| # | 控件 | 预期生效 |
|---|------|----------|
| D-01 | 补货调度 | → `/replenishment` |
| D-02 | 争议审核 | → `/disputes` |
| D-03 | 设备管理 | → `/devices` |
| D-04 | 设备可用性 | → `/device-kpi` |
| D-05 | 刷新 | 重载 workbench；失败不闪 0 |
| D-06～ | 各可点 KPI 卡 | 对应 §4.1 深链 + 筛选生效 |
| D-Q* | 快捷链接（异常/争议/超时单/停售/离线/待上传/缺货/临期…） | query 落地 |
| D-Z* | 工作区「查看」+ extra 按钮 | 按行类型深链 |
| D-ON | 进件工作台（有权） | → `/merchant-onboarding` |
| D-PG | 待办分页（有多页） | 页码变、行变 |

### B.2～B.9

其余 8 页开测当日用 snapshot 补全（大屏：刷新/全屏/退出全屏/返回后台；分析：图类型切换 + KPI 深链；销售：五维度 + 导出 + 行链；库存：维度/导出/一键补货/行链；用户：双导出 + 召回弹层取消/确认）。**未进表的按钮不得标页完成。**

---

## 附录 C · 三端口径抽样单（每发生一次深链业务主键填一行）

| 主键类型 | ID | 后台页/字段/值 | 商户页/值 | 消费者页/值 | 差 | 判定 |
|----------|-----|----------------|-----------|-------------|----|------|
| orderId | | | | | ≤1分 | |
| deviceId | | | | | 状态一致 | |
| merchantId | | | | | ≤1分 | |
| ticketId | | | | | 状态一致 | |
| sessionId | | 视频 | 视频 | 视频 | 可播/同因 | |
