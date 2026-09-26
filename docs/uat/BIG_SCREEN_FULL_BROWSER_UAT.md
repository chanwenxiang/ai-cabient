# 运营大屏 · 全量浏览器 UAT（单页深测册）

> **地位**：「概览 → 运营大屏」**单页执行真源**。比 [`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §4.2 更细：按钮清点、数据口径、跨页对账、三端口径、UX/UI。  
> **工具铁律**：宣称通过前必须用 **Playwright MCP / CLI** 真实打开、点击、断言（规则 `playwright-ui-testing`）。禁止仅凭 curl / 日志。  
> **质量四维**：① 数据正确 ② 排版布局 ③ 交互体验 ④ 三端口径统一。仅「点得开」不算 PASS。  
> **源码**：`clients/admin-vue/src/views/dashboard/BigScreenView.vue`  
> **API 聚合**：`stats` + `workbench` + `sla` + `financeStats` + `trend(10)` + 设备排行/销售报表/地图点位/`dataScope`  
> **版本**：1.0 · 2026-09-26 · Commit 以执行时 `git rev-parse --short HEAD` 为准

---

## 0. 元信息（执行时填写）

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP + 微信开发者工具 automator |
| 日期 | 2026-09-26 |
| Commit | `0b18a6dd` |
| 环境 | Docker 全栈（gateway `:80` / trade `:18080`） |
| 视口 | 1366×768 主测；抽检 1920×1080 |
| 账号 | 运营 `13900000001`；商户 mp `13800138001`；消费者 mp `13800138000` |
| 工具 | Playwright MCP + DevTools CLI（auto-port 9420） |
| 截图目录 | `docs/uat-screenshots/2026-09-26/big-screen/` |
| 结案明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/big-screen/BUTTONS.md) + [`FINDINGS.md`](../uat-screenshots/2026-09-26/big-screen/FINDINGS.md) |
| 统计 | PASS 全控件+口径+跨模块+三端 / FAIL 0 / BLOCK 0 / 口径说明 2（投放排行 vs 全量营收；离线列表分母） |

### Pre-flight

```text
✓ docker / trade / gateway UP
✓ http://localhost/admin/index.html 可登录
✓ 双端 dist/dev/mp-weixin 可开（三端口径只认 mp，不认 H5）
```

---

## 1. 页面结构与数据口径

### 1.1 区块地图

| 区 | 内容 |
|----|------|
| A 页头 | 品牌「AI 开门柜」· 标题「售货机运营态势大屏」· 时钟 · 刷新 / 全屏 / 返回后台 |
| B KPI | 售货机总数 · 今日营收 · 今日订单 · 今日毛利 · 设备在线率 · 待处理争议（**本页 KPI 不可点深链**） |
| C 左栏 | 销售趋势（近 10 日）· 品类构成（近 30 天）· 区域销售对比（今日） |
| D 中栏 | Leaflet/高德地图点位 · 在线/离线图例 · 有坐标 n/投放总数 |
| E 右栏 | 点位销售排行（今日 Top5）· 点位运营明细 · 待办/告警（前 N · 共 M） |

### 1.2 KPI / 列表口径（与工作台对账）

| UI | 主字段 | 对账端 |
|----|--------|--------|
| 售货机总数 | `stats.deviceTotal`（投放） | hint「在售 n」=`devicesOnSale` |
| 今日营收 | `stats.revenueTodayCents` → `¥` | 工作台 KPI；商户 mp 今日营收 |
| 今日订单 | `stats.orderToday` | hint 累计 `orderTotal` |
| 今日毛利 | `finance.grossMarginTodayCents` | hint 毛利率 |
| 设备在线率 | `sla.deviceOnlineRate` | hint「投放离线 n」=`offlineDevices`；与工作台对 |
| 待处理争议 | `workbench.openDisputes` | 工作台；hint「逾期 n」=`overdueDisputes` |
| 排行/明细 | **仅投放柜**营收排序 | lessons #199；禁含非投放 |
| 待办预览 | `actionItems` 前 N，标题「前 N · 共 M」 | 与 workbench.actionItems.length |
| 地图有坐标 | `mappedPoints.length / fleetDeviceTotal` | 文案已对齐 KPI 投放口径 |

**深链**：本页业务 KPI/排行/待办 **不可点击跳转**（产品设计）。仅「返回后台」→ `/dashboard`。跨模块对账用 **同数字** 去工作台/订单/争议页核对，不要求本页出链。

---

## 2. 用例清单（BS-*）

### 2.1 首屏与刷新

| ID | 步骤 | 期望 |
|----|------|------|
| BS-01 | 登录后打开 `/big-screen` | 标题「售货机运营态势大屏」；暗色驾驶舱；无整页白 |
| BS-02 | 等 hydrated | KPI/图/地图加载完成；失败中文空态，不闪假绿 |
| BS-03 | Network 关键请求 | stats/workbench/… `code=0` 或诚实 soft-fail |
| BS-04 | 点「刷新」 | loading 时按钮 disabled；数字可与新响应对齐 |

### 2.2 顶栏控件

| ID | 控件 | 期望 |
|----|------|------|
| BS-H-01 | 刷新 | 见 BS-04 |
| BS-H-02 | 全屏 | 进入 `document.fullscreenElement`；文案变「退出全屏」 |
| BS-H-03 | 退出全屏 | 退出全屏；文案恢复「全屏」；无侧栏残留 |
| BS-H-04 | 返回后台 | → `/dashboard`；工作台可渲染 |

自动化环境若浏览器策略禁全屏 → **SKIP** + 截图按钮存在，不阻塞结案（须在 BUTTONS 注明）。

### 2.3 口径对账（必做）

| ID | 步骤 | 期望 |
|----|------|------|
| BS-D-01 | 大屏今日营收 vs 工作台 | 同日 `revenueTodayCents`，差 0 分 |
| BS-D-02 | 大屏在线率/离线 hint vs 工作台 | 投放分母一致；离线数一致 |
| BS-D-03 | 待审争议 vs 工作台 openDisputes | 一致 |
| BS-D-04 | 待办「前 N · 共 M」 | M = actionItems.length；N ≤ M 且 N≤预览上限 |
| BS-D-05 | 排行仅投放柜 | 排行 deviceId ⊆ DEPLOYED 集合 |

### 2.4 地图 / 图表 / 列表

| ID | 步骤 | 期望 |
|----|------|------|
| BS-M-01 | 地图区 | 有点或诚实「暂无设备点位」；图例在线/离线可读 |
| BS-M-02 | 有坐标 meta | `有坐标 a/b`，b=投放总数 |
| BS-C-01 | 销售趋势图 | 有 series 或「暂无趋势数据」 |
| BS-C-02 | 品类/区域图 | 同上 |
| BS-L-01 | 点位排行 | Top≤5；金额 `¥` 短格式 |
| BS-L-02 | 运营明细 | 列：点位/营收/订单/状态；状态中文在线/离线 |
| BS-L-03 | 待办列表 | 有 title；空则「暂无待办」 |

### 2.5 跨模块追测（对账，非本页深链）

| ID | 起点 | 追测 | 止点 |
|----|------|------|------|
| BS-X-01 | 今日营收 | 打开工作台 / 财务 | 金额一致 |
| BS-X-02 | 待审争议 | 打开 `/disputes?status=OPEN` | total ↔ KPI |
| BS-X-03 | 离线 hint | 打开设备离线筛选 | 列表量级可对 |
| BS-X-04 | 排行首柜 | 设备详情（若有 id） | 柜机存在 |

### 2.6 三端口径（仅 mp-weixin）

| 主题 | 大屏 | 商户 mp | 消费者 mp | 判据 |
|------|------|---------|-----------|------|
| 今日营收 | KPI | 首页今日营收 | — | 差 0 分（或角色口径差须文档说明） |
| 离线柜 | hint / 地图 | 柜机列表灰点 | — | 同 deviceId |
| 争议数 | 待审争议 | 争议待审核 | — | 运营 OPEN vs 商户可见子集允许不等，记说明 |

### 2.7 UX / UI

| ID | 检查 |
|----|------|
| BS-UX-01 | 1366×768 无整页横向滚动 |
| BS-UX-02 | 1920×1080 抽检无严重裁切重叠 |
| BS-UX-03 | 全屏后无后台侧栏/顶栏残留 |
| BS-UX-04 | 中文空态/错误；金额 `¥` |
| BS-UX-05 | 刷新有 loading 反馈 |

---

## 3. 按钮清点模板（附录 B）

| # | 控件 | 类型 | 预期 | ✓ |
|---|------|------|------|---|
| D-01 | 刷新 | button | 重载 | |
| D-02 | 全屏 / 退出全屏 | button | Fullscreen API | |
| D-03 | 返回后台 | link | `/dashboard` | |
| D-KPI-* | 四枚 KPI | display | 不可点；口径对 | |
| D-map / D-charts / D-lists | 展示区 | display | 有数或诚实空 | |

---

## 4. 结案勾选

- [x] §2.1～2.4 本页控件 + 展示区
- [x] §2.3 口径 vs 工作台
- [x] §2.5 跨模块 ≥2（实做 4）
- [x] §2.6 三端口径（仅 mp）
- [x] §2.7 UX + 截图
- [x] BUTTONS.md / FINDINGS.md 已写

---

## 5. 关联

| 文档 | 关系 |
|------|------|
| `OVERVIEW_FULL_BROWSER_UAT.md` §4.2 | 概览摘要；细则以本册为准 |
| `WORKBENCH_FULL_BROWSER_UAT.md` | 同源 stats/workbench 对账 |
| lessons #199 | 排行仅投放柜；待办「前 n · 共 N」 |
