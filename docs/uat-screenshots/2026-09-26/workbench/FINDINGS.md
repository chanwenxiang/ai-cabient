# 运营工作台 UAT · FINDINGS · 2026-09-26

> 工具：Playwright MCP（运营后台）+ 微信开发者工具 CLI/automator（双端 **mp-weixin**）  
> 文档：[`WORKBENCH_FULL_BROWSER_UAT.md`](../../../uat/WORKBENCH_FULL_BROWSER_UAT.md)  
> Commit：`0b18a6dd`（开测时）· 环境 Docker 全栈  
> **铁律**：小程序只认 DevTools `dist/dev/mp-weixin`；**禁止用 H5 冒充三端 PASS**

---

## 结论摘要

| 维度 | 结果 |
|------|------|
| 本页按钮 / KPI / 区卡深链 | **PASS**（含零计数 UI 点击、KPI 键盘 a11y） |
| 零计数直链冒烟 | **PASS**（含诚实空态） |
| 跨模块追测 §2.6 | **PASS 7/7**（见 BUTTONS B.3） |
| 三端口径（mp-weixin） | **PASS**（营收/柜机/订单 ¥3.50/分账¥3.15↔钱包 API） |
| 本页缺陷 | **2 FAIL 已修且静态复测 PASS** |
| 空态 WB-Q-09 | **SKIP**（有待办无法清空调） |

---

## 后台 KPI ⇄ API（workbench-bundle）

| UI | API | 判 |
|----|-----|----|
| 在售 1 / 停售 2 | devicesOnSale=1, devicesSalesLocked=2 | ✓ |
| 在线率 0.0%（0/1） | deviceOnline=0, deviceTotal=1（投放） | ✓ |
| 今日营收 ¥3.50（造单后） | 新单 `1790420215052990851425` PAID 350¢ | ✓ |
| 待处理异常 12 | openExceptionCount=12 | ✓ |
| 待审争议 8 | openDisputes=8 | ✓ |
| 离线设备 1 | offlineDevices=1 | ✓ |
| 对账 1 / 分账 20 | mismatches=1, splitExceptions=20 | ✓ |
| 明细 15 条 · 入口合计 44 | actionItemsLen=15 | ✓ |

截图：`wb-01-open.png`

---

## 三端口径（权威：mp-weixin）

### A. 今日营收 / 离线柜

| 端 | 值 | 取证 |
|----|-----|------|
| 运营后台工作台 | 今日营收 ¥0.00；离线设备 1 | Playwright + bundle API |
| 商户 mp-weixin | 今日营收 ¥0.00；离线柜 1；在线柜机 1/2 | DevTools · `wb-mp-m-home.png` |
| 差 | 0 | **PASS**（商户「在线 1/2」含其全部柜；后台 KPI 在线率仅投放柜 0/1——口径说明见下） |

**口径说明**：后台「设备在线率」= `stats.deviceOnline/deviceTotal`（**仅投放 DEPLOYED**）；商户「在线柜机」= 商户名下全部柜。二者分母不同，**不得**要求数字相等；应用同一 `deviceId` 对状态。

### B. 设备 `777740024057`

| 端 | 状态 | 取证 |
|----|------|------|
| 运营后台 | 离线 · 已锁机 · 投放 | 深链 `/devices/777740024057` |
| 商户 mp | 灰点离线 · 停售 badge · 投放 | `wb-mp-m-devices.png` |
| 差 | 一致 | **PASS** |

### C. 订单实付（历史样例）`1790242469027192236737`

| 端 | 金额 | 状态 | 取证 |
|----|------|------|------|
| 运营后台订单列表 | ¥3.50 | 已支付 | API `ops/orders`（未单独截图；同单见 `wb-mp-c-orders.png`） |
| 消费者 mp-weixin | ¥3.50 | 已支付 | DevTools · `wb-mp-c-orders.png` |
| 商户 mp 争议建议（同价 SKU 口径） | 建议 ¥3.50 | 待审核 | `wb-mp-m-disputes.png` |
| 差 | 0 分 | | **PASS** |

### C2. 本轮新消费 `1790420215052990851425`（2026-09-26 18:56）

| 端 | 金额 | 状态 | 取证 |
|----|------|------|------|
| 运营后台 | ¥3.50 · 今日营收 ¥3.50 | PAID | `ops/admin/orders` + 工作台 KPI |
| 消费者 mp-weixin | ¥3.50 · 可口可乐 330ml x1 | 已支付 | DevTools · `wb-mp-c-new-order.png` |
| 商户 | ¥3.50 | PAID | `GET /api/v2/merchant/orders` 首条同 orderId |
| 差 | 0 分 | | **PASS** · 详见 `wb-new-order-three-end.json` |

### D. 待审争议数量

| 端 | 值 | 取证 |
|----|-----|------|
| 后台工作台 | 8 | zone + API |
| 商户 mp 争议「待审核」 | 列表多条 OPEN（与后台同源） | `wb-mp-m-disputes.png` |
| 消费者「需要关注」待确认 | 4（消费者视角子集，≠运营 OPEN 全集） | `wb-mp-c-orders.png` |

运营 OPEN 与消费者「待确认」**允许数量不同**（权限/角色过滤）；金额样例以订单主键为准。

---

## 缺陷与修复

| ID | 现象 | 根因 | 修复 |
|----|------|------|------|
| WB-BUG-01 | 告警「待处理明细 15 条」但分页「共 10 条」、下一页 disabled，分账行不可达 | `fetchPage` 返回纯数组 → `normalizeListPage` 把本页长度当 total | `DashboardView.vue` 改为 `{ items, total: filteredActions.length }` |
| WB-BUG-02 | 履约异常角标 32 = 异常12+争议8+再加12 | `workZones.fulfill.total` 在区卡已含「异常中心」后又 `+ openExceptionCount` | 去掉二次相加 |

生效复测（2026-09-26 19:00+）：`node scripts/build-admin.mjs` → gateway 挂载 `trade-service/.../static/admin` → 实载 `DashboardView-jvDvQRVa.js`。

| 项 | 复测结果 | 截图 |
|----|----------|------|
| 分页 | **共 15 条** · 10条/页 · 有第 2 页；点第 2 页可见分账异常行，下一页 disabled | `wb-dash-page2-badge.png` |
| 履约角标 | **20** = 异常中心 12 + 待审争议 8（**无双计**） | 同上 |

**禁止**：再用 PowerShell `Set-Content` 改 mp `vendor.js`（会 UTF-8 损坏 → Unterminated string）。改 API 基址只改 `.env.development` + `uni build`。

---

## UX 巡检（1366×768）

| ID | 结果 |
|----|------|
| WB-UX-01～05 | PASS：无整页横滚；KPI/区卡可点；刷新有 loading |
| WB-UX-06 | PASS：`listHydrated` 前不闪假「运行正常」 |
| 可选优化 | 零待办默认隐藏可发现性依赖「分区显示 0 待办」勾选（产品设计，非 FAIL） |

---

## 结案勾选

- [x] §2.1～2.5 本页控件（零计数 UI+直链、KPI 键盘）
- [x] §2.6 跨模块 **7/7**
- [x] §2.7 三端口径 ≥1 主键（订单 + 设备 + 分账金额）· **仅 mp**
- [x] §2.8 UX + 截图
- [x] BUTTONS.md / 本文
- [x] 源码修复静态复测分页 / 履约角标（`DashboardView-jvDvQRVa.js`）
- [x] WB-Q-09 空态 **SKIP**（有数据；文案源码已核对）
