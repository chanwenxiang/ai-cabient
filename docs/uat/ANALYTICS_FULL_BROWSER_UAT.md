# 数据分析 · 全量浏览器 UAT（单页深测册）

> **地位**：「概览 → 数据分析」**单页执行真源**。比 [`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §4.3 更细：按钮清点、深链落地、口径对账、三端、UX。  
> **工具铁律**：宣称通过前必须用 **Playwright MCP / CLI** 真实打开、点击、断言（规则 `playwright-ui-testing`）。禁止仅凭 curl / 日志。  
> **质量四维**：① 数据正确 ② 排版布局 ③ 交互体验 ④ 三端口径统一。仅「点得开」不算 PASS。  
> **源码**：`clients/admin-vue/src/views/analytics/AnalyticsView.vue`  
> **API**：`stats` + `trend(days)` + `trendOps(days)` + `financeStats` + `trendChannels(days)`  
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
| 截图目录 | `docs/uat-screenshots/2026-09-26/analytics/` |
| 结案明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/analytics/BUTTONS.md) + [`FINDINGS.md`](../uat-screenshots/2026-09-26/analytics/FINDINGS.md) |
| 统计 | PASS 全控件+深链+跨模块+三端 / FAIL 0 / BLOCK 0 / 口径说明 1（投放离线 vs 列表） |

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
| A 页头 | 标题「数据分析」· hint「上方为今日 / 近 24 小时快照，不受下方趋势范围影响」·「刷新」 |
| B KPI×4 | 今日营收 → `/finance`；今日订单 → `/orders`；24h 开门成功率 → `/sessions`；24h 自动识别率 → `/disputes` |
| C 趋势工具栏 | 「今天 / 近 7 / 30 / 90 天」→ URL `?days=` 同步 |
| D 营收/订单图 | 折线 / 面积 / 柱状切换 |
| E 渠道 | 订单支付渠道 · 充值渠道（甜甜圈 + 中文图例） |
| F 识别质量 | 折线/面积/柱；自动识别率 vs 争议率 |
| G 侧栏 | 设备在线（「查看离线 n」→ `/devices?online=OFFLINE`）· 经营快照（待审争议 / 今日毛利率 → finance） |

### 1.2 口径要点

| UI | 口径 | 对账 |
|----|------|------|
| 顶栏 KPI | **今日 / 24h 快照**，切 `days` **不改** KPI | vs `stats` / 工作台 / 大屏 |
| 趋势与渠道 | 随 `days` 变；`days=1` 文案「今天」禁止「近 1 天」 | vs `trend` / `trendChannels` |
| 设备离线 | `deviceTotal - deviceOnline` | vs 投放分母；列表可能含非投放 |
| 待审争议 | `stats.disputeOpen` | vs `/disputes?status=OPEN` total |

---

## 2. 用例清单（AN-*）

### 2.1 首屏与刷新

| ID | 步骤 | 期望 |
|----|------|------|
| AN-01 | 打开 `/analytics` | 标题「数据分析」；KPI 非假 0 闪烁后 hydrated |
| AN-02 | Network | stats/trend… `code=0` 或块级降级 |
| AN-03 | 「刷新」 | loading；数字可对齐 |

### 2.2 KPI 深链

| ID | 控件 | 期望 |
|----|------|------|
| AN-K-01 | 今日营收 | → `/finance` |
| AN-K-02 | 今日订单 | → `/orders` |
| AN-K-03 | 24h 开门成功率 | → `/sessions` |
| AN-K-04 | 24h 自动识别率 | → `/disputes` |

### 2.3 趋势范围与图型

| ID | 步骤 | 期望 |
|----|------|------|
| AN-R-01 | 今天 / 7 / 30 / 90 | URL `?days=`；图重载；KPI 不变 |
| AN-C-01～03 | 营收·订单·识别 折线/面积/柱 | 切换无报错；有图或「暂无…」 |

### 2.4 侧栏深链

| ID | 控件 | 期望 |
|----|------|------|
| AN-S-01 | 查看离线 n（n>0） | → `/devices?online=OFFLINE` |
| AN-S-02 | n 条待审 | → `/disputes?status=OPEN` |
| AN-S-03 | 查看毛利率 | → `/finance` |

### 2.5 跨模块 / 三端 / UX

| ID | 步骤 | 期望 |
|----|------|------|
| AN-X-01 | 今日营收 vs 工作台/大屏 | 同 `revenueTodayCents` |
| AN-X-02 | 待审争议 vs 列表 | total 一致 |
| AN-X-03 | 离线 vs 设备筛选 | 量级可解释 |
| AN-T-01 | 商户 mp 今日营收 | 与后台一致（mp-weixin） |
| AN-U-01 | 1366 / 1920 | 无整页白卡横滚；中文 |

---

## 3. 结案清单

- [x] 本页按钮/KPI/图型全点
- [x] KPI 口径 vs API
- [x] 深链落地 ≥ KPI+侧栏
- [x] 跨模块 ≥2
- [x] 三端营收抽样（mp）
- [x] UX 双视口
- [x] BUTTONS / FINDINGS / 本册 §0 统计

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 刷新 / days / 图型×9 | PASS；KPI 切天数不变 |
| KPI×4 + 侧栏×3 深链 | 全部落地目标页 |
| 跨模块 | 工作台/财务 ¥3.50；争议共 8；离线 1 vs 列表 2（说明） |
| 三端 | 后台 = 商户 mp 今日营收 ¥3.50 = 350¢ |
| UX | 1366 / 1920 无整页横滚 |
|
