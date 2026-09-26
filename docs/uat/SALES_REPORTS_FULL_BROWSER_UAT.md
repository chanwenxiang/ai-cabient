# 销售报表 · 全量浏览器 UAT（单页深测册）

> **地位**：「概览 → 销售报表」**单页执行真源**。比 [`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §4.7 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。  
> **源码**：`clients/admin-vue/src/views/reports/SalesReportsView.vue`  
> **API**：`GET /api/v2/ops/admin/sales-reports?dim=&fromDate=&toDate=` · `…/export`  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768 DPR=1；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/sales-reports/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/sales-reports/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/sales-reports/FINDINGS.md) |
| 统计 | PASS · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 页头 | 标题 · 导出（无选中→后端；有选中→页内 CSV） |
| 筛选 | 维度×5 · 柜机 · 快捷今日/7/30 · 日期区间 · 查询/重置 |
| KPI×5 | 订单数 · 营收 · 退款 · 净营收 · 毛利 |
| 表 | 编码/名称可深链 · 营收/退款/毛利服务端排序 · Crud 刷新/列设置 |

**深链**：商品/毛利→`/skus?keyword=`；货柜→`/devices/{id}`；商户→`/merchants?tab=splits&merchantId=`；渠道无链。

---

## 2. 用例（SR-*）

| ID | 步骤 | 期望 |
|----|------|------|
| SR-01 | 打开 `/sales-reports` | 默认近7天；KPI+表 |
| SR-02 | 维度 商品/货柜/商户/渠道/毛利 | 表头稳定；行数随维变 |
| SR-03 | 快捷 今日/近7/近30 | KPI 变（今日1单→7天2单→30天10单） |
| SR-04 | 柜机筛选 | 营收收窄（全柜¥7.00→单柜¥3.50） |
| SR-05 | 自定义区间 + 查询 | 生效 |
| SR-06 | 重置 | 回 商品 + 近7天 |
| SR-07 | 表头排序 营收/退款/毛利 | 可点 |
| SR-08 | 导出无选中 / 有选中 | `sales-reports.csv` · `销售报表_*.csv` |
| SR-09～12 | 深链 | skus / devices / merchants splits / skus |
| SR-X-01 | KPI vs API 近7天 | 一致 |
| SR-U-01 | 900 视口 | 无整页横滚 |

---

## 3. 结案清单

- [x] 维度×5 / 快捷 / 柜机 / 区间 / 查询重置
- [x] 排序 / 双路径导出
- [x] 深链×4
- [x] KPI = API
- [x] BUTTONS / FINDINGS

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 默认近7天 KPI | 订单2 · 营收¥7.00 · 退款¥0 · 净营收¥7.00 · 毛利¥3.20（= API 700/320¢） |
| 柜机筛选 | 330449777078 → 营收¥3.50 |
| 导出 | 后端 `sales-reports.csv`；选中 `销售报表_*.csv` |
| 深链 | 商品/毛利/货柜/商户 全 PASS；渠道无 dim-link（设计） |
|
