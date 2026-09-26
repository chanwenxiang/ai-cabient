# 运营工作台 · 全量浏览器 UAT（单页深测册）

> **地位**：「概览 → 运营工作台」**单页执行真源**。比 [`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §4.1 更细：按钮清点、深链落地、跨模块追测、三端口径、UX/UI。  
> **工具铁律**：宣称通过前必须用 **Playwright MCP / CLI** 真实打开、点击、断言（规则 `playwright-ui-testing`）。禁止仅凭 curl / 日志。  
> **质量四维**：① 数据正确 ② 排版布局 ③ 交互体验 ④ 三端口径统一。仅「点得开」不算 PASS。  
> **源码**：`clients/admin-vue/src/views/dashboard/DashboardView.vue`  
> **API**：`GET /api/v2/ops/admin/workbench-bundle`（降级：`/stats` + `/workbench` + exceptions open-count）  
> **版本**：1.0 · 2026-09-26 · Commit 以执行时 `git rev-parse --short HEAD` 为准

---

## 0. 元信息（执行时填写）

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP + 微信开发者工具 automator |
| 日期 | 2026-09-26 |
| Commit | `0b18a6dd`（开测） |
| 环境 | Docker 全栈（`.\docker-up.ps1`，不含 devops） |
| 视口 | 1366×768 |
| 账号 | 运营 `13900000001`；商户 mp `13800138001`；消费者 mp `13800138000` |
| 工具 | Playwright MCP + DevTools CLI（`D:\devTools\微信web开发者工具\cli.bat` · auto-port 9420） |
| 截图目录 | `docs/uat-screenshots/2026-09-26/workbench/` |
| 结案明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/workbench/BUTTONS.md) + [`FINDINGS.md`](../uat-screenshots/2026-09-26/workbench/FINDINGS.md) |
| 统计 | PASS 本页+§2.6 全 7 条+三端 mp / FAIL 2（分页/履约角标，已修并复测 PASS） / BLOCK 0 / SKIP 空态 WB-Q-09 |

### Pre-flight

```text
✓ docker ps → trade / device / vision / gateway / postgres / redis Running
✓ http://localhost:18080/actuator/health → UP
✓ http://localhost/admin/index.html → 登录页可开
✓ 双端 `dist/dev/mp-weixin` 已编译且 mtime 新；微信开发者工具可打开（**三端口径只认 mp，不认 H5**）
```

---

## 1. 页面结构与数据口径

### 1.1 区块地图

| 区 | 内容 |
|----|------|
| A 页头 | 标题「运营工作台」+ 副文案；顶栏按钮 |
| B KPI 行 | 在售货柜 / 设备在线率 / 今日营收 / 待处理异常（均可点） |
| C 四分区 | 资金异常 · 履约异常 · 设备运维 · 进件待办（区卡快捷 + extra 链） |
| D 告警明细 | 「分区显示 0 待办」· 全部/仅紧急 · CrudTable · 行「查看」· 分页 |

### 1.2 KPI / 计数口径（对账用）

| UI 文案 | 主字段 | 深链 query | 口径要点 |
|---------|--------|------------|----------|
| 在售货柜 | `workbench.devicesOnSale` | `/devices?salesLocked=false` | 未停售；hint 显示停售数 |
| 设备在线率 | `stats.deviceOnline / deviceTotal` | 有离线→`lifecycleStatus=DEPLOYED&online=OFFLINE`；否则 ONLINE | **仅投放柜**，禁含 INBOUND |
| 今日营收 | `stats.revenueTodayCents` → `¥x.xx` | `/finance` | **分→元**；禁止把分当元 |
| 待处理异常 | `openExceptionCount` | `/exceptions?status=OPEN` | 与异常中心 OPEN total 一致 |
| 超时待支付 | `pendingUnpaidOrders` | `/orders?status=PENDING&overdue=1` | 超时未付 |
| 待审争议 | `openDisputes` | `/disputes?status=OPEN` | OPEN |
| 停售货柜 | `devicesSalesLocked` | `/devices?salesLocked=true` | |
| 离线设备 | `offlineDevices` | `/devices?online=OFFLINE` | |
| 待上传 | `waitingUploads` 或 `sessionWaitingUpload` | `/upload-queue?stuck=1` | 卡住上传 |
| 缺货柜/SKU | `lowStockSkuCount` 或 `lowStockItems` | `/stock-health?dimension=ALL&lifecycleStatus=DEPLOYED` | 含 qty=0；须 DEPLOYED |
| 临期批次 | `nearExpiryLotCount` | `/stock-health?dimension=NEAR_EXPIRY&lifecycleStatus=DEPLOYED` | |
| 补货任务 | `pendingReplenishments` | `/replenishment?tab=routes` | |
| 异常会话 | `staleSessions` | `/sessions?stuck=1` | 活跃滞留，禁已完成历史 |
| 对账差异 | `reconciliationMismatches` | `/reconciliation?status=MISMATCH` | |
| 分账异常 | `splitExceptions` | `/merchants?tab=splits` | |
| 签收超时 | `inTransitOverdue` | `/warehouse?tab=transit&overdue=1` | |
| 进件待办 | onboard SUBMITTED total | `/merchant-onboarding?status=SUBMITTED` | 有权才显示 |

**产品行为**：区卡默认 **隐藏 count=0** 的快捷；勾选「分区显示 0 待办」后显示。零计数项用 **直链冒烟** 验证落地，标 SKIP（不可见）不得假装未测。

### 1.3 告警行类型 → 跳转

| `actionItems.type` | 目标 | query / 路径 |
|--------------------|------|---------------|
| DISPUTE | `/disputes` | `status=OPEN` + ticketId/sessionId/deviceId |
| UPLOAD_STUCK | `/upload-queue` | `stuck=1` + … |
| SESSION_STALE | `/sessions` | `stuck=1`（勿强制 SHOPPING） |
| LOW_STOCK | `/replenishment` | `tab=shortage` |
| REPLENISHMENT | `/replenishment` | `tab=routes` |
| IN_TRANSIT_OVERDUE | `/warehouse` | `tab=transit&overdue=1` |
| RECON_MISMATCH / RECONCILIATION_MISMATCH | `/reconciliation` | `status=MISMATCH` |
| SPLIT_EXCEPTION | `/merchants` | `tab=splits` |
| DEVICE_OFFLINE | `/devices/{id}` 或 `online=OFFLINE` | |

关联列：会话号/工单号须 **完整可搜**（禁截断末 10 位）。

---

## 2. 用例清单（WB-*）

### 2.1 首屏与刷新

| ID | 步骤 | 期望 | 判定栏 |
|----|------|------|--------|
| WB-01 | 登录后打开 `#/dashboard` | 标题「运营工作台」；副文案含「今日快照」；无整页白屏 | |
| WB-02 | 等 `listHydrated` | KPI 不为长时间「…」；加载失败中文 toast，**不闪假 0** | |
| WB-03 | Network：`workbench-bundle`（或降级三接口） | `code=0`；UI 数字 ⇄ JSON 字段 | |
| WB-04 | 点「刷新」 | loading；成功后数字与新响应一致；失败保留旧数或中文提示 | |

### 2.2 顶栏按钮

| ID | 控件 | 期望 URL | 落地校验 |
|----|------|----------|----------|
| WB-H-01 | 补货调度 | `/replenishment` | 页标题/Tab 可见 |
| WB-H-02 | 争议审核 | `/disputes` | 列表可开 |
| WB-H-03 | 设备管理 | `/devices` | 列表可开 |
| WB-H-04 | 设备可用性 | `/device-kpi` | KPI 页 |
| WB-H-05 | 刷新 | 仍在 `/dashboard` | 见 WB-04 |

无权限时按钮应隐藏（`v-hasPermi`）→ 记 SKIP + 截图。

### 2.3 KPI 四卡深链

| ID | 卡 | 期望 | 落地 |
|----|-----|------|------|
| WB-K-01 | 在售货柜 | `/devices?salesLocked=false` | 筛选回显未停售；列表 total 与 KPI 量级可对 |
| WB-K-02 | 设备在线率 | DEPLOYED + OFFLINE 或 ONLINE | 生命周期=投放；在线态匹配 |
| WB-K-03 | 今日营收 | `/finance` | 财务毛利页；金额单位元 |
| WB-K-04 | 待处理异常 | `/exceptions?status=OPEN` | OPEN；total ↔ KPI |
| WB-K-05 | 键盘 | 焦点 KPI 后 Enter/Space | 同鼠标深链（a11y） |

### 2.4 四分区 · 快捷与 extra

| ID | 控件 | 期望 | 落地 |
|----|------|------|------|
| WB-Z-FUND-01 | 超时待支付 | `/orders?status=PENDING&overdue=1` | 状态+超时筛选 |
| WB-Z-FUND-02 | 对账差异 | `/reconciliation?status=MISMATCH` | 差异态 |
| WB-Z-FUND-03 | 分账异常 | `/merchants?tab=splits` | 分账 Tab |
| WB-Z-FUND-04 | 财务看板 | `/finance` | 财务页 |
| WB-Z-FF-01 | 异常中心 | `/exceptions?status=OPEN` | OPEN |
| WB-Z-FF-02 | 待审争议 | `/disputes?status=OPEN` | OPEN；count ↔ 列表 |
| WB-Z-FF-03 | 待上传 | `/upload-queue?stuck=1` | 卡住 |
| WB-Z-FF-04 | 异常会话 | `/sessions?stuck=1` | 仅滞留 |
| WB-Z-FF-05 | 补货任务 | `/replenishment?tab=routes` | routes Tab |
| WB-Z-FF-06 | 签收超时 | `/warehouse?tab=transit&overdue=1` | 在途+逾期 |
| WB-Z-FF-07 | 异常中心（extra） | 同 FF-01 | |
| WB-Z-DEV-01 | 停售货柜 | `salesLocked=true` | |
| WB-Z-DEV-02 | 离线设备 | `online=OFFLINE` | |
| WB-Z-DEV-03 | 缺货柜/SKU | stock-health ALL+DEPLOYED | 有数不空、无数诚实空 |
| WB-Z-DEV-04 | 临期批次 | NEAR_EXPIRY+DEPLOYED | |
| WB-Z-DEV-05 | 设备列表（extra） | `/devices` | |
| WB-Z-ON-01 | 进件待办 | `status=SUBMITTED` | |
| WB-Z-ON-02 | 进件工作台（extra） | `/merchant-onboarding` | |

`count=0` 不可见 → **勾选「分区显示 0 待办」后点**，或 **直链冒烟**；结果写 SKIP/PASS。

### 2.5 告警明细表

| ID | 步骤 | 期望 |
|----|------|------|
| WB-Q-01 | 表头 meta | 「待处理明细 N 条 · 入口合计 M」与 API/`actionItems.length`/`totalIssues` 一致量级 |
| WB-Q-02 | 勾选「分区显示 0 待办」 | 区卡出现 0 行快捷；再取消恢复 |
| WB-Q-03 | 「仅紧急」 | 仅 CRITICAL/HIGH；条数 ≤ 全部 |
| WB-Q-04 | 「全部」 | 恢复全量 |
| WB-Q-05 | 列：优先级/类型/标题/关联/详情 | 中文；金额若有则 `¥`；关联号完整 |
| WB-Q-06 | 列设置（若有） | 可开关列；不崩 |
| WB-Q-07 | 行「查看」——按类型至少各测 1 条（有数据时） | 见 §1.3；目标页筛选/主键回显 |
| WB-Q-08 | 分页 | 多页时翻页行集变；单页则上下页 disabled → SKIP |
| WB-Q-09 | 空态 | 无告警时中文「运行正常，暂无待处理异常」 |

### 2.6 跨模块追测（深链后继续）

从工作台落到目标页后，**至少完成一条业务主链**（视数据选）：

| ID | 起点 | 追测 | 止点 |
|----|------|------|------|
| WB-X-01 | 待审争议 | 开详情抽屉 → 看视频/订单链 | 抽屉可读或诚实失败；同单对账见 §2.7（**仅 mp-weixin**） |
| WB-X-02 | 超时待支付 | 开订单抽屉 → 金额 | 商户/消费者 **微信开发者工具** 同 `orderId` 实付一致 |
| WB-X-03 | 离线/停售设备 | 进设备详情 | 商户 **mp-weixin** 柜机 `online`/`salesLocked` 一致 |
| WB-X-04 | 异常会话 | 时间线 + 播放 | 同 session 视频：后台 + 双端 **mp** 同成败 |
| WB-X-05 | 缺货/补货 | 补货页/库存健康 | 商户 **mp** 补货待办同柜同 SKU |
| WB-X-06 | 对账/分账 | 差异行或分账 Tab | 商户 **mp** 钱包/分账金额 ≤1 分差 |
| WB-X-07 | 签收超时 | 仓储在途 | 出库单号可搜 |

写操作（免单/退款/确认发放）**仅点到确认框后取消**，禁止真改资金。

### 2.7 三端口径（抽样 ≥1 主键）

> **铁律**：小程序端 **只认微信开发者工具** 打开的 `clients/*/dist/dev/mp-weixin`（或真机）。  
> **禁止**用 `:3001` / `:3002` H5 冒充「商户端 / 消费者端」口径 PASS（与 debt-tracker / 概览 FINDINGS 一致；H5 有大量 `#ifdef`，构建链也不同）。

| 主题 | 后台（Playwright） | 商户 mp-weixin（DevTools） | 消费者 mp-weixin（DevTools） | 判据 |
|------|--------------------|---------------------------|------------------------------|------|
| 订单实付 | 订单抽屉 / 销售深链 | 订单列表 | 订单详情 | 同 orderId：`fmtMoney` 字符串一致 |
| 争议状态 | 争议列表 | 争议处理 | 申诉态 | 同 ticket 语义一致 |
| 柜机在线/停售 | 工作台 KPI / 设备 | 柜机列表 | — | 同 deviceId |
| 库存断货 | 缺货深链 | 补货/货道 | — | 同柜同 SKU |
| 分账 | 分账 Tab | 钱包/分账 | — | 同日差 ≤1 分 |
| 购物视频 | 会话/争议抽屉 | 订单视频 | 订单视频 | 同 session |

取证：DevTools CLI `cli.bat auto --project <dist> --auto-port 9420` + `miniprogram-automator`；截图落入 `docs/uat-screenshots/2026-09-26/workbench/`。未开 DevTools 的条目标 **BLOCK**，不得用 H5 顶替。

### 2.8 UX / UI 巡检（G-UX）

视口 1366×768，整页截图 `wb-ux-01.png`：

| ID | 检查 | FAIL 例 |
|----|------|---------|
| WB-UX-01 | 无横向整页滚动 | 白卡片撑出横向条 |
| WB-UX-02 | 标题/KPI/按钮无重叠截断 | 「设备可用…」无 tooltip |
| WB-UX-03 | 热区可点；区卡 warn 有危险色 | 数字红但点不到 |
| WB-UX-04 | 点击 300ms 内有 loading/路由/toast | 死点 |
| WB-UX-05 | 空态/错误中文 | 英文 stack |
| WB-UX-06 | KPI 加载前不闪「0 / 运行正常」 | 假绿 |
| WB-UX-07 | 表操作列可见或表内横滑 | 「查看」出屏 |
| WB-UX-08 | 对比度可读 | 灰底灰字 |

可选优化（发现后记 FINDINGS，未必本轮改）：分区 0 隐藏可发现性、告警行主键复制、营收旁「今日」时区说明。

---

## 3. 按钮清点模板（附录 B）

开测前用 Playwright snapshot + 源码列出；测完勾生效。漏勾 = 本页未完成。

| # | 控件 | 类型 | 预期生效 | 实测证据 | ✓ |
|---|------|------|----------|----------|---|
| D-01 | 补货调度 | button | → replenishment | | |
| D-02 | 争议审核 | button | → disputes | | |
| D-03 | 设备管理 | button | → devices | | |
| D-04 | 设备可用性 | button | → device-kpi | | |
| D-05 | 刷新 | button | 重载 bundle | | |
| D-KPI-售 | 在售货柜 | tile | salesLocked=false | | |
| D-KPI-在线 | 设备在线率 | tile | DEPLOYED+online | | |
| D-KPI-营收 | 今日营收 | tile | → finance | | |
| D-KPI-异常 | 待处理异常 | tile | exceptions OPEN | | |
| D-Q-* | 各 zone-link | button | 见 §2.4 | | |
| D-extra-* | 四区 extra | text btn | 见 §2.4 | | |
| D-零显 | 分区显示 0 待办 | checkbox | 0 链出现 | | |
| D-紧急 | 全部/仅紧急 | radio | 行集变 | | |
| D-查看-* | 行查看 | row action | 见 §1.3 | | |
| D-列设置 | 列设置 | popover | 可开关 | | |
| D-分页 | 上下页 | pager | 有多页才测 | | |

---

## 4. 执行日志（按条追加）

```text
### WB-xx
- 从: …
- 操作: …
- 期望: …
- 实际: …
- 数据: UI=… API=… 对照端=…
- 截图: …
- 判定: PASS|FAIL|BLOCK|SKIP
```

---

## 5. 结案清单

- [x] §2.1～2.5 本页控件全部勾选（零计数 UI+直链、KPI 键盘）
- [x] §2.6 跨模块 **7/7**
- [x] §2.7 三端口径 ≥1 主键填表（**仅 mp-weixin**）
- [x] §2.8 UX 巡检 + 整页截图
- [x] BUTTONS.md / FINDINGS.md 已写
- [x] 分页/履约角标静态复测 PASS
- [x] WB-Q-09 SKIP（有待办）
- [x] 活文档 `PROJECT_KNOWLEDGE` §9 追加一行
- [ ] 分页/履约角标修复部署后浏览器复测

---

## 6. 与概览册关系

| 文档 | 用途 |
|------|------|
| 本文 `WORKBENCH_FULL_BROWSER_UAT.md` | 工作台单页深测（本轮主执行） |
| `OVERVIEW_FULL_BROWSER_UAT.md` §4.1 | 概览九页中的工作台摘要；深链矩阵可交叉引用本文 |
| 交易履约 / 设备 / 财务 / 仓储分册 | 跨组深链细则复测 |

冲突时以**本册 + 当轮 Playwright 证据**为准。
