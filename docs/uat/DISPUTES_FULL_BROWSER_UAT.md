# 争议审核 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「交易履约 → 争议审核」**单页执行真源**。比 [`FULFILLMENT_FULL_BROWSER_UAT.md`](./FULFILLMENT_FULL_BROWSER_UAT.md) §3.3 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。免单/落账一律 **确认框后取消**（禁真退）。  
> **源码**：`clients/admin-vue/src/views/disputes/DisputeListView.vue`  
> **API**：`GET /api/v2/ops/disputes`（**非** `/ops/admin/disputes`）  
> **视口**：主测 **1366×768**；窄/宽用 MCP `browser_resize`，**禁止**在长脚本里反复 `setViewportSize`+`goto`（会弹回 1366）。  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768（DPR≈1）；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/disputes/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/disputes/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/disputes/FINDINGS.md) |
| 统计 | PASS · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| Tab | 全部类型 / 识别争议 |
| 识别子筛 | 全部识别 · 低置信 · 兜底 · 重力错配/回填 · 未映射 · 空识别 · 需复核 · 白名单 |
| 筛选 | 状态（中文）· 关键词 · 查询/重置 |
| 表 | 导出 · 刷新 · 列设置 · 行详情 |
| 抽屉工作台 | 录像门控勾选 · 认领 · 免单/调整落账 · 异常中心 · 关联订单 · 设备/会话链 |

---

## 2. 用例（DSP-*）

| ID | 步骤 | 期望 |
|----|------|------|
| DSP-01 | `?status=OPEN` | 状态「待审核」无裸 OPEN；total↔API |
| DSP-02 | 识别争议 Tab + 子码 | 列表变化或诚实空 |
| DSP-03 | 关键词=工单号 | ≥1 行 |
| DSP-04 | 导出 | CSV 下载 |
| DSP-05 | 详情工作台 | 工单/会话/设备/金额可读 |
| DSP-06 | 认领工单 | toast 含处理人 |
| DSP-07 | 无录像→已对照→免单 | 确认框 → **取消**；仍待审核 |
| DSP-08 | 调整落账（若可点） | 确认 → 取消 |
| DSP-09 | 异常中心 / 关联订单 | 深链生效 |
| DSP-10 | 设备 / 会话链 | `/devices` · `/sessions` |
| DSP-U-01 | 900 视口 | 无整页横滚；**resize 后勿 goto** |

---

## 3. 结案清单

- [x] OPEN 中文状态 · total=API(8)
- [x] 认领 / 免单取消 / 调整取消
- [x] 异常·订单·设备·会话深链
- [x] 导出 CSV
- [x] 视口 1366 钉死；900/1920 抽检
- [x] BUTTONS / FINDINGS / Changelog

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| OPEN | 共 8 条 = API；状态「待审核」 |
| 全量 | API total 13 |
| 认领 | toast「已认领：运营超管」 |
| 免单 | 双勾选门控 → 确认取消（未真退） |
| 导出 | `争议_20260926_*.csv` |
| 视口结束 | vw=1366 · 窄 900 无整页横滚 |
|
