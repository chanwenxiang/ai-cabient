# 用户分析 · 全量浏览器 UAT（单页深测册）

> **地位**：「概览 → 用户分析」**单页执行真源**。比 [`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §4.9 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。  
> **源码**：`clients/admin-vue/src/views/growth/UserAnalysisView.vue`  
> **API**：`GET /api/v2/ops/admin/growth/user-analysis?days=` · `POST …/growth/user-recall`  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/user-analysis/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/user-analysis/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/user-analysis/FINDINGS.md) |
| 统计 | PASS · FAIL 0 · BLOCK 0 · 召回弹层 N/A（无沉睡用户） |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 页头 | days 7/30/90 · 导出沉睡 · 导出复购 · 刷新 |
| KPI×7 | 活跃/新增/复购/沉睡/累计用户/订单营收/客单价 |
| 左表 | 复购 TOP10 |
| 右表 | 沉睡名单 · 「一键召回」（需 coupon:create + 名单非空） |
| 弹层 | 选券 · 取消 · 确认发放并通知 |

---

## 2. 用例（UA-*）

| ID | 步骤 | 期望 |
|----|------|------|
| UA-01 | 打开 `/user-analysis` | KPI + 双表中文 |
| UA-02 | 刷新 | 成功 |
| UA-03 | 导出沉睡名单 | 有数据则 CSV；空则中文「暂无数据可导出」 |
| UA-04 | 导出复购榜 | CSV 下载 |
| UA-05 | 一键召回 | 有沉睡则开弹层；无则 disabled |
| UA-06 | 弹层取消 | 有弹层时关闭 |
| UA-07 | 确认发放 | 有弹层+券时 toast；本环境无沉睡 → N/A |
| UA-08 | 消费者侧 | 可选；本轮跳过 |
| UA-X-01 | KPI vs API days=30 | 一致 |
| UA-U-01 | 900 视口 | 无整页横滚 |

---

## 3. 结案清单

- [x] days / 刷新 / 双导出
- [x] KPI = API
- [x] 复购表有数据；沉睡空态诚实
- [x] 一键召回 disabled 门控（无名单）
- [x] BUTTONS / FINDINGS

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| KPI（30 天） | 活跃2 · 新增2 · 复购1(50%) · 沉睡0 · 用户2 · 订单24 · 营收¥33.50 · 客单¥1.40 |
| 复购 TOP | 陈晓 13800138000 · 23 单 · ¥33.50 |
| 沉睡 | 空表；导出 toast「暂无数据可导出」；一键召回 disabled |
| 导出复购 | `复购用户TOP10_*.csv` |
|
