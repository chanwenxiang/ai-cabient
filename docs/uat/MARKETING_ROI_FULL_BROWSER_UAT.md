# 活动效果分析 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「增长风控 → 活动效果分析」**单页执行真源**。比 [`GROWTH_RISK_FULL_BROWSER_UAT.md`](./GROWTH_RISK_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。本页**无硬写**（筛选/导出/刷新只读）。  
> **源码**：`clients/admin-vue/src/views/growth/MarketingRoiView.vue`  
> **API**：`GET /api/v2/ops/admin/growth/marketing-roi?days=`（无分页；关键词前端过滤）  
> **视口**：主测 **1366×768**（DPR=1）。  
> **版本**：1.0 · 2026-09-27

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-27 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/marketing-roi/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/marketing-roi/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/marketing-roi/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「活动效果分析」· hint（发券→核销→营收；预算已用 vs 订单优惠）· 近 7/30/90 天 |
| 筛 | 关键词（活动名称，前端过滤）· 查询 · 重置 |
| 表 | 活动/类型/状态/预算/预算已用/发券数/核销数/核销率/订单优惠/带动订单/带动营收 · 空态「暂无活动数据」 |
| 工具 | 导出 CSV · 刷新 · 列设置 |
| 写 | **无** |

---

## 2. 用例（ROI-*）

| ID | 步骤 | 期望 |
|----|------|------|
| ROI-01 | 打开 `/marketing-roi` | 标题·共 N↔API；默认近 30 天 |
| ROI-02 | 关键词无匹配 | 「暂无活动数据」· 共 0 |
| ROI-03 | 重置 | 关键词清空；列表恢复 |
| ROI-04 | 近 7 / 90 天 | 切档请求 `?days=`；空态或共 N |
| ROI-05 | 导出 | 有数据→CSV；空→toast「暂无数据可导出」 |
| ROI-06 | 刷新 | 可点 |
| ROI-U-* | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 空态共 0↔API；近 7/30/90 切档；关键词空/重置
- [x] 空表导出 toast「暂无数据可导出」；刷新可点
- [x] UX 900/1366/1920；无硬写
- [x] BUTTONS / FINDINGS / Changelog；GROWTH_RISK GR-ROI-02

---

## 4. 执行摘要（2026-09-27）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **0** ·「暂无活动数据」· API `days=30/7/90` 均 length=0（与营销活动空态一致） |
| 筛 | 关键词无匹配仍空 · 重置清空 |
| 导出 | toast「暂无数据可导出」（空表预期） |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
