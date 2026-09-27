# 余额退款 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「财务商户 → 余额退款」**单页执行真源**。比 [`FINANCE_MERCHANT_FULL_BROWSER_UAT.md`](./FINANCE_MERCHANT_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。通过/驳回仅 prompt→取消。  
> **源码**：`clients/admin-vue/src/views/finance/BalanceRefundView.vue`  
> **API**：`GET …/balance-refunds` · `POST …/balance-refunds/{id}/review`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/balance-refunds/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/balance-refunds/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/balance-refunds/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 「余额退款申请」· hint（用户充值页提交 · 原路退）· 批量通过/驳回 |
| Tab | 待审核 · 已退款 · 已驳回 · 失败 · 全部（localStorage 记忆） |
| 筛 | 关键词（申请号/单号/用户ID，**前端过滤**）· 查询 · 重置 |
| 表 | 申请号/用户(深链 users)/金额¥/状态/原因/备注/失败原因/时间 · 导出/刷新 |

---

## 2. 用例（BR-*）

| ID | 步骤 | 期望 |
|----|------|------|
| BR-01 | 打开（待审核） | 共 0↔API；暂无申请 |
| BR-02 | 批量通过/驳回 | 无勾选 disabled |
| BR-03 | 关键词无匹配 | 空态 |
| BR-04 | Tab 全部 | 共 0↔API |
| BR-05 | Tab 已退款/已驳回/失败 | 各共 0↔API |
| BR-06/07 | 通过/驳回 → 取消 | 有待审则 prompt 取消；本机 **SKIP** |
| BR-08 | 用户深链 | 有行则 `/users?keyword=`；本机 SKIP |
| BR-09/10 | 导出 / 刷新 | 可点 |
| BR-U-01/03 | 视口 | 900 无横滚 · 结束 1366 |

---

## 3. 结案清单

- [x] 五 Tab 空态诚实 ↔ API total=0
- [x] 批量门控 disabled；行审核/用户链 SKIP
- [x] BUTTONS / FINDINGS / Changelog；FINANCE_MERCHANT 深链  
- [ ] 有待审数据时补测通过/驳回取消（见 FINDINGS 口径）

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 列表 | 全 Tab **0** · 暂无申请 |
| 写路径 | 行审核 SKIP（无 PENDING） |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
