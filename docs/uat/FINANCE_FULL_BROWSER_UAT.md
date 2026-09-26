# 财务毛利 · 全量浏览器 UAT（单页深测册）

> **地位**：「概览 → 财务毛利」**单页执行真源**。比 [`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §4.6 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。三端口径只认 mp-weixin / 已对齐营收。  
> **源码**：`clients/admin-vue/src/views/finance/FinanceView.vue`  
> **API**：`GET /api/v2/ops/admin/finance/report?days=` · `POST …/finance/margin-locks/solidify`  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 环境 | Docker 全栈 gateway `:80` / trade `:18080` |
| 视口 | 固定 1366×768 DPR=1；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/finance/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/finance/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/finance/FINDINGS.md) |
| 硬写 | 「固化昨日毛利」confirm → **取消** |
| 统计 | PASS · FAIL 0 · BLOCK 0 · 口径说明 1 |

---

## 1. 结构与口径

| 区 | 内容 |
|----|------|
| 页头 | 标题 · 固化昨日毛利 · 刷新 |
| Alert | 毛利固化规则说明 |
| KPI×6 | 今日营收/成本/毛利/毛利率/订单/客单；营收→analytics、订单→orders |
| 天数 | 今天 / 近7 / 近30 / 近90 · URL `?days=` |
| 毛利趋势 | 折线/面积/柱状 · 营收/成本/毛利 |
| 累计快照 | 累计营收/成本/毛利 · 今日报废金额/件数 |
| 商品毛利 TOP | CrudTable：多选/升序降序/导出/列设置/清空 · 商品管理 |

---

## 2. 用例（FIN-*）

| ID | 步骤 | 期望 |
|----|------|------|
| FIN-01 | 打开 `/finance` | KPI + 图 + 表中文 |
| FIN-02 | 刷新 | 成功重载 |
| FIN-03 | 固化昨日毛利 | MessageBox → **取消** |
| FIN-04 | days 1/7/30/90 | URL 同步；hint「近 N 天」 |
| FIN-05 | `?days=15` 非法 | toast 回退近 1 天 |
| FIN-06 | 图型 折线/面积/柱状 | 可切换 |
| FIN-07 | 商品管理 | → `/skus` |
| FIN-08 | KPI 今日营收 | → `/analytics`（含键盘 Enter） |
| FIN-09 | KPI 今日订单 | → `/orders` |
| FIN-10 | 导出 CSV | 下载 `商品毛利TOP_*.csv` |
| FIN-11 | 升序/降序/勾选/清空/列设置 | 可用 |
| FIN-X-01 | KPI vs API days=1 | 一致 |
| FIN-T-01 | 三端营收抽样 | admin ¥3.50 = 350¢（历史造单） |
| FIN-U-01 | 900/1366/1920 | 900 无整页横滚 |

---

## 3. 结案清单

- [x] 控件全点（含固化取消）
- [x] KPI vs API
- [x] days / URL / 非法回退
- [x] 深链 analytics / orders / skus
- [x] 导出 CSV
- [x] 窄视口
- [x] BUTTONS / FINDINGS

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| KPI×6 | 营收¥3.50 / 成本¥1.90 / 毛利¥1.60 / 45.7% / 订单2 / 客单¥1.75 = API |
| 累计快照 | ¥33.50 / ¥19.00 / ¥14.50；报废 0 |
| TOP SKU | `SKU-DEMO-001` 可口可乐 · 毛利¥1.60 |
| 硬写 | 固化确认框中文 → 取消 |
| 深链 | analytics / orders / skus 全 PASS |
| UX | 900：vw=docW=900 无整页横滚 |
|
