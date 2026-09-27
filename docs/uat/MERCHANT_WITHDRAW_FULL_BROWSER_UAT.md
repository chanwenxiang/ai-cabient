# 商户提现 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「财务商户 → 商户提现」**单页执行真源**。比 [`FINANCE_MERCHANT_FULL_BROWSER_UAT.md`](./FINANCE_MERCHANT_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。写路径仅确认→取消（调账/代提现/审核）。  
> **源码**：`clients/admin-vue/src/views/finance/MerchantWithdrawView.vue`  
> **API**：`GET …/merchant-wallets` · `…/ledgers` · `POST …/adjust|withdraw` · `GET/POST …/merchant-withdraws*` · payout-mode  
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
| 截图 | `docs/uat-screenshots/2026-09-26/merchant-withdraw/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/merchant-withdraw/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/merchant-withdraw/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 1（深链 tab）· BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 标题「商户提现」· hint「手续费…到账=申请−手续费」· 刷新；提现 Tab 另显批量通过/驳回 |
| Alert | payout-mode：记账打款（未接真实转账）· fee=0 |
| Tab | 商户钱包 · 提现审核 |
| 钱包筛 | 关键词（商户编号/名称/手机）· 查询 |
| 钱包表 | 编号/名称/电话/余额/冻结/可用/状态 · 操作：调账·流水·更多→代提现 |
| 提现筛 | 状态（待审核…失败）· 查询 · 批量门控 |
| 提现表 | 单号/业务单号/商户/金额/手续费/状态/通道/回执… · 终态无操作列 |

---

## 2. 用例（MW-*）

| ID | 步骤 | 期望 |
|----|------|------|
| MW-01 | 打开 `/merchant-withdraw` | 共 2↔API；余额两位小数；状态「正常」；记账 Alert |
| MW-02 | 调账 → 取消 | 弹层「商户调账」· 不落库 |
| MW-03 | 流水 | 抽屉「钱包流水」 |
| MW-04 | 更多→代提现 → 取消 | 弹层「代商户提现」 |
| MW-05 | 关键词无匹配 | 共 0 · 暂无商户钱包 |
| MW-06 | 提现审核 Tab | 共 1 · 已打款 · ¥1.00 |
| MW-07 | 批量通过/驳回 | 无勾选 disabled |
| MW-08 | 状态下拉 | 待审核/已通过/打款中/已打款/已驳回/失败 |
| MW-09 | 筛已打款 | 共 1 · MW-FULL… |
| MW-11 | 行通过并打款 | SKIP（无待审） |
| MW-12 | `?tab=withdraws` | **FINDING**：URL 保留 · Tab 仍「商户钱包」 |
| MW-13 | 刷新 | 可点 |
| MW-U-01 | 900 | 无整页横滚 |
| MW-U-03 | 结束 | 1366×768 DPR≈1 |

---

## 3. 结案清单

- [x] 钱包 2 / 提现 1 ↔ API；记账 Alert
- [x] 调账·代提现取消；流水抽屉；关键词空态
- [x] 状态中文筛；批量 disabled；行审核 SKIP
- [x] FINDING：`?tab=` 未同步（与线长钱包同型）
- [x] BUTTONS / FINDINGS / Changelog；FINANCE_MERCHANT 深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 钱包 | 共 **2**（MCH-DEFAULT ¥29.15 · MCH-OTHER ¥0） |
| 提现 | 共 **1** · 已打款 · Mock 回执 |
| 写路径 | 调账/代提现取消 · 行审核 SKIP |
| 深链 | `?tab=` **未切 Tab**（FINDING） |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
