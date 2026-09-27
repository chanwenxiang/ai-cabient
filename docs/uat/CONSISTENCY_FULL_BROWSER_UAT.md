# 数据一致性 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「财务商户 → 数据一致性」**单页执行真源**。比 [`FINANCE_MERCHANT_FULL_BROWSER_UAT.md`](./FINANCE_MERCHANT_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。修复须确认→取消；立即巡检为只记未通过、不自动改数（可实跑）。  
> **源码**：`clients/admin-vue/src/views/consistency/ConsistencyView.vue`  
> **API**：`GET …/consistency/failures` · `POST …/consistency/run` · `POST …/consistency/{id}/fix`  
> **视口**：主测 **1366×768**（DPR=1）。  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/consistency/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/consistency/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/consistency/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 标题「数据一致性」· hint · **立即巡检** |
| Alert | 一致性巡检说明（只记未通过、不自动改数；可修类型 vs 需人工） |
| KPI | 未通过 · 高优先级（有则显）· 本页 · 上次巡检 |
| 筛 | 关键词（键/表/说明）· 类型 · 查询 · 重置（**前端过滤**） |
| 表 | 类型/键/表/级别/基准/对照/说明/状态/检出时间 · 修复或需人工 |

---

## 2. 用例（CON-*）

| ID | 步骤 | 期望 |
|----|------|------|
| CON-01 | 打开 `/consistency` | 空态诚实 ↔ API 0；Alert；立即巡检 |
| CON-02 | 类型下拉 | ≥20 项中文（订单金额/钱包…），无 ORDER_AMOUNT 裸码 |
| CON-03 | 关键词无匹配 | 「无匹配未通过记录」· 共 0 |
| CON-04 | 类型筛 | 有失败行则收窄；本机空 SKIP |
| CON-05 | 修复 → 取消 | 有可修行则 MessageBox「显式修复」取消；本机 SKIP |
| CON-06 | 立即巡检 | toast「巡检完成：全部通过」· 上次巡检 KPI |
| CON-07 | 键深链 | 有行则跳订单/设备等；本机 SKIP |
| CON-08 | 刷新 | 可点 |
| CON-U-01 | 900 | 无整页横滚 |
| CON-U-03 | 结束 | 1366×768 DPR≈1 |

---

## 3. 结案清单

- [x] 空态 0 ↔ API；类型中文 21 项；关键词空态诚实（前端滤）
- [x] 立即巡检全部通过；修复/键链 SKIP（无失败行）
- [x] BUTTONS / FINDINGS / Changelog；FINANCE_MERCHANT 深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 列表 | 未通过 **0** · 空态引导再巡检 |
| 巡检 | toast 全部通过 · 上次巡检时间戳 |
| 写路径 | 修复 SKIP；巡检实跑（不改业务数） |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
