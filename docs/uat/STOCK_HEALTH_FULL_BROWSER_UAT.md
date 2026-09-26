# 库存健康 · 全量浏览器 UAT（单页深测册）

> **地位**：「概览 → 库存健康」**单页执行真源**。比 [`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §4.8 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。  
> **源码**：`clients/admin-vue/src/views/reports/StockHealthView.vue`  
> **API**：`GET /api/v2/ops/admin/reports/stock-health` · `…/export`  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/stock-health/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/stock-health/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/stock-health/FINDINGS.md) |
| 硬写 | 「报损」confirm→取消；「下架」无确认 → **本轮未点** |
| 统计 | PASS · FAIL 0 · BLOCK 0 · 口径说明 2 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 页头 | 一键补货规划（N 台）· 导出 |
| 筛选 | 维度×4 · 柜机 · 商户 · 路线 · 生命周期 · 查询 |
| KPI×4 | 断货行 · 低库存行 · 临期行 · 涉及柜机 |
| 表 | 维度 Tag · 设备/SKU/库存… · 行操作：设备 / 补货 /（临期）下架·报损 |

**默认生命周期 = DEPLOYED（投放）**；演示异常库存多在 **INBOUND**，需 `lifecycleStatus=ALL` 才见数。

---

## 2. 用例（SH-*）

| ID | 步骤 | 期望 |
|----|------|------|
| SH-01 | 打开 `/stock-health` | KPI；默认可为空（仅投放） |
| SH-02 | 维度 全部/断货/低库存/临期 | 切换无崩 |
| SH-03 | `?dimension=STOCKOUT` | 回显「断货」 |
| SH-04 | `lifecycleStatus=ALL` | KPI 有数：断货6 / 低1 / 柜机2 |
| SH-05 | 柜机筛选 | 行收窄 |
| SH-06 | 商户+路线+查询 | 可过滤（R1 无匹配→0 行诚实） |
| SH-07 | 导出无选中/有选中 | `stock-health.csv` · `库存健康_*.csv` |
| SH-08 | 一键补货规划 | → `/replenishment?tab=shortage`（plan/deviceIds 入弹层后清 URL） |
| SH-09 | 行「设备」 | → `/devices/{id}` |
| SH-10 | 行「补货」 | → shortage |
| SH-11 | 报损 | 有则 confirm→取消；本环境临期=0 → N/A |
| SH-U-01 | 900 视口 | 无整页横滚 |

---

## 3. 结案清单

- [x] 维度 / URL / 生命周期 ALL
- [x] 柜机·商户·路线筛选
- [x] 双路径导出
- [x] 一键规划 + 行设备/补货
- [x] KPI vs API（ALL）
- [x] BUTTONS / FINDINGS

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 默认 DEPLOYED | KPI 全 0（演示柜未投放）— 诚实空态 |
| lifecycle=ALL | 断货6 · 低库存1 · 临期0 · 柜机2 · plan=[330449777078,CAB-001] |
| 深链 | 设备/补货/一键规划 PASS |
| 临期下架/报损 | 无临期行 → N/A |
|
