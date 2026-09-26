# 运营后台 ·「交易履约」全量浏览器 UAT（Phase 2）

> **地位**：第二册执行真源（侧栏「交易履约」：订单 / 开门 / 争议 / 异常；工作台深链常进「录像上传」一并测）。  
> **工具铁律**：同 [`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §1（Playwright 实测；四维：数据 / 排版 / UX / 三端口径）。  
> **版本**：1.0 · 2026-09-26  
> **截图**：`docs/uat-screenshots/2026-09-26/fulfillment/`

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 日期 | 2026-09-26（侧栏四页复测同日） |
| 环境 | Docker 全栈（不含 devops）· `http://localhost/admin/` |
| 视口 | 1366×768 |
| 账号 | `13900000001` / `123456` + 验证码 |
| 工具 | Playwright MCP（与概览同款：附录 B 编号 + FINDINGS） |
| 结案 | **Phase 2 Done（复测）**；明细 [`fulfillment/BUTTONS.md`](../uat-screenshots/2026-09-26/fulfillment/BUTTONS.md) + [`FINDINGS.md`](../uat-screenshots/2026-09-26/fulfillment/FINDINGS.md) |

---

## 1. 范围与顺序

| 序 | 路径 | 标题 |
|----|------|------|
| 1 | `/orders` | 订单管理 |
| 2 | `/sessions` | 开门记录 |
| 3 | `/disputes` | 争议审核（概览已深测；本册补写路径确认框） |
| 4 | `/exceptions` | 异常中心 |
| 5 | `/upload-queue` | 录像上传（设备商品组；履约深链必测） |

工作台深链（须再验筛选生效）：

| 来源 | 目标 |
|------|------|
| 待审争议 | `/disputes?status=OPEN` |
| 待处理异常 | `/exceptions?status=OPEN` |
| 超时未付 | `/orders?status=PENDING&overdue=1` |
| 异常会话 | `/sessions?stuck=1` |
| 上传卡住 | `/upload-queue?stuck=1`（或 keyword/deviceId） |

---

## 2. 用例 ID 前缀

`FULFILL-ORD-*` · `FULFILL-SES-*` · `FULFILL-DSP-*` · `FULFILL-EXC-*` · `FULFILL-UP-*`

---

## 3. 关键页必过（摘要）

### 3.1 订单 `/orders`

> **单页深测真源**（2026-09-26）：[`ORDERS_FULL_BROWSER_UAT.md`](./ORDERS_FULL_BROWSER_UAT.md) · `orders/BUTTONS`/`FINDINGS`（含 #204 已退款+隐藏零元）。

| ID | 检查 | 期望 |
|----|------|------|
| FULFILL-ORD-01 | 列表加载 | 有行或中文空态；金额 `¥x.xx` |
| FULFILL-ORD-02 | Tab 状态 | 待支付/已支付等切换后行集或空态变化 |
| FULFILL-ORD-03 | 点订单号开抽屉 | 抽屉有订单号、金额与列表同行一致 |
| FULFILL-ORD-04 | 抽屉：会话/设备链 | 跳转 sessions/devices 且 query 生效 |
| FULFILL-ORD-05 | 抽屉：播放录像 | 有片可播或诚实失败中文 |
| FULFILL-ORD-06 | 全额/按行退款 | **仅点到确认框后取消**（禁真退） |
| FULFILL-ORD-07 | 导出 | 触发下载或「无数据」toast |
| FULFILL-ORD-08 | overdue 深链 | query 生效；无数据时诚实空 |

### 3.2 开门 `/sessions`

> **单页深测真源**（2026-09-26）：[`SESSIONS_FULL_BROWSER_UAT.md`](./SESSIONS_FULL_BROWSER_UAT.md) · `sessions/BUTTONS`/`FINDINGS`（含 #195 滞留、#205 类型 total）。

| ID | 检查 | 期望 |
|----|------|------|
| FULFILL-SES-01 | 列表 | total 与 API 一致量级 |
| FULFILL-SES-02 | 仅滞留 | **仅活跃态超时**；禁已完成历史（#195） |
| FULFILL-SES-03 | 时间线抽屉 | 步骤可读；设备/订单链可用 |
| FULFILL-SES-04 | 播放 / 上传队列 | 跳转或中文失败 |

### 3.3 争议 `/disputes`

> **单页深测真源**（2026-09-26）：[`DISPUTES_FULL_BROWSER_UAT.md`](./DISPUTES_FULL_BROWSER_UAT.md) · `disputes/BUTTONS`/`FINDINGS`。

| ID | 检查 | 期望 |
|----|------|------|
| FULFILL-DSP-01 | OPEN 筛选 | 中文「待审核」 |
| FULFILL-DSP-02 | 认领 | 处理人更新 |
| FULFILL-DSP-03 | 免单/调整确认框 | 出框后**取消**；状态仍 OPEN |

### 3.4 异常 `/exceptions`

> 单页深测真源：[`EXCEPTIONS_FULL_BROWSER_UAT.md`](./EXCEPTIONS_FULL_BROWSER_UAT.md)

| ID | 检查 | 期望 |
|----|------|------|
| FULFILL-EXC-01 | OPEN Tab | 计数 ↔ API |
| FULFILL-EXC-02 | 详情/处理 | 抽屉或页可开；无白屏 |
| FULFILL-EXC-03 | 「全部」Tab | URL `status=ALL`；total↔无 status API（禁回弹 OPEN，#207） |

### 3.5 录像上传 `/upload-queue`

| ID | 检查 | 期望 |
|----|------|------|
| FULFILL-UP-01 | 列表 | 有数或中文空态 |
| FULFILL-UP-02 | stuck/device 深链 | 筛选回显 |

---

## 4. 执行日志（本轮）

见 `docs/uat-screenshots/2026-09-26/fulfillment/FINDINGS.md`（若目录未建则写在 overview FINDINGS「Phase 2」节）。

---

## 5. Done 定义

- [x] 上表 ID 均有 PASS/FAIL/SKIP + 证据（见 `fulfillment/FINDINGS.md` + `BUTTONS.md`）  
- [x] 资金写路径未误提交（确认框取消）  
- [x] 发现的易复发问题已进 lessons + Changelog（#195–#197）  
- [x] **2026-09-26 复测**：侧栏四页按概览同款附录 B 编号重测；截图 `ff-*.png`；争议免单须先「无录像」再「已对照」  
