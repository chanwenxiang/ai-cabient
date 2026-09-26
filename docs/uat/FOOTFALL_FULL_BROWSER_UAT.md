# 客流坪效 · 全量浏览器 UAT（单页深测册）

> **地位**：「概览 → 客流坪效」**单页执行真源**。比 [`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §4.4 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。三端口径只认 mp-weixin。  
> **源码**：`clients/admin-vue/src/views/analytics/FootfallView.vue`  
> **API**：`GET …/analytics/footfall?days=` · `…/analytics/footfall/slots?deviceId=&days=`  
> **版本**：1.0 · 2026-09-26 · Commit `9aa090f0`

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP + DevTools automator |
| 日期 | 2026-09-26 |
| 环境 | Docker 全栈 gateway `:80` / trade `:18080` |
| 视口 | 1366×768 + 抽检 1920×1080 / 窄视口横滚 |
| 账号 | 运营 `13900000001`；商户 mp `13800138001` |
| 截图 | `docs/uat-screenshots/2026-09-26/footfall/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/footfall/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/footfall/FINDINGS.md) |
| 统计 | PASS 全控件+KPI+表+窄视口+三端抽样 / FAIL 0 / BLOCK 0 / 口径说明 1 |

---

## 1. 结构与口径

| 区 | 内容 |
|----|------|
| 页头 | 「客流坪效」· days 下拉 7/30/90 · 刷新 |
| KPI×6 | 开门次数 · 支付订单 · 开门转化率 · 客单价 · 复购用户 · 柜机数 |
| 时段热区 | 24h 柱（订单量） |
| 柜机坪效表 | 柜机/开门/订单/转化/营收（¥）；opens=0 且有单 → 转化「—」 |
| 商品热区 TOP20 | 商品/SKU/销量/营收/件均价/占比（¥） |
| 货道热区 | 柜机下拉 → slot heat 格子；无数据中文空态 |

**深链**：本页 **无** router 深链（N/A）。跨模块对账：开门/订单量级 vs 会话/订单列表抽样。

---

## 2. 用例（FF-*）

| ID | 步骤 | 期望 |
|----|------|------|
| FF-01 | 打开 `/footfall` | 标题；KPI/表中文；非假 0 闪错 |
| FF-02 | 近 7 / 30 / 90 | 数据重载；KPI 可随天数变 |
| FF-03 | 刷新 | 成功 |
| FF-04 | 窄视口柜机表 | 表内可横滚，**非整页白卡裁切** |
| FF-05 | 货道柜机下拉 | 切换后热区变或诚实空态 |
| FF-06 | SKU/柜机金额列 | `¥` 格式 |
| FF-X-01 | KPI 开门/订单 vs API | 一致 |
| FF-T-01 | 商户 mp 今日订单量级 | 可对照（非同 API，记口径） |
| FF-U-01 | 1366 / 1920 | 无整页横滚 |

---

## 3. 结案清单

- [x] 控件全点（days/刷新/货道下拉）
- [x] KPI vs API
- [x] 表金额 ¥ · 转化「—」语义
- [x] 窄视口布局
- [x] 三端抽样
- [x] BUTTONS / FINDINGS

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 刷新 / 7·30·90 | PASS；KPI 随天数变（7→29 开；30→57 开） |
| KPI×6 | 与 footfall?days=7 API 一致 |
| 表 / 热区 | ¥ 格式；发号柜转化「—」；货道 A1 可见 |
| UX | 900/1366/1920 无整页横滚 |
| 三端 | 商户无同页；首页 ¥3.50 抽样 |
| 深链 | N/A |
|
