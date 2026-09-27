# 线长钱包 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「财务商户 → 线长钱包」**单页执行真源**。比 [`FINANCE_MERCHANT_FULL_BROWSER_UAT.md`](./FINANCE_MERCHANT_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。写路径仅确认→取消（新建/调账/审核/绑柜）。  
> **源码**：`clients/admin-vue/src/views/finance/LineManagerView.vue`  
> **API**：`GET/POST …/line-managers*` · `…/withdraws*` · `…/promo-tasks*` · payout-mode  
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
| 截图 | `docs/uat-screenshots/2026-09-26/line-managers/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/line-managers/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/line-managers/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 1（深链 tab）· BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 标题「线长钱包」· hint「与商户分账解耦…」· 新建线长 · 刷新；提现 Tab 另显批量通过/驳回 |
| Alert | payout-mode：记账打款（未接真实转账）· fee=0 |
| Tab | 线长成员 · 提现审核 · 地推任务 |
| 成员筛 | 关键词（姓名/手机）· 状态（启用/停用）· 查询 |
| 成员表 | 经理编号/姓名/手机/组织/绑定用户/OpenID/余额/冻结/绑柜/佣金/状态 · 空态「暂无线长」 |
| 提现筛 | 状态（待审核…失败）· 查询 · 批量门控 |
| 地推 | 新建任务 · 空态「暂无地推任务」 |

---

## 2. 用例（LM-*）

| ID | 步骤 | 期望 |
|----|------|------|
| LM-01 | 打开 `/line-managers` | 标题正确；共 0↔API；暂无线长；记账 Alert |
| LM-02 | 新建线长 → 取消 | 弹层「新建线长」· 不落库 |
| LM-03 | 成员状态下拉 | 启用/停用（无 ACTIVE 裸码） |
| LM-04 | 关键词无匹配 | 共 0 · 暂无线长 |
| LM-05 | 行调账 | 有行则 MessageBox→取消；本机 SKIP |
| LM-06 | 行绑柜 | 有行则弹层→取消；本机 SKIP |
| LM-07 | 提现审核 Tab | 暂无提现申请 · 共 0 |
| LM-07b | 提现状态下拉 | 待审核/已通过/打款中/已打款/已驳回/失败 |
| LM-08 | 批量通过/驳回 | 无勾选 disabled |
| LM-09 | 行通过并打款 | 有待审则确认→取消；本机 SKIP |
| LM-10 | 地推任务 Tab | 暂无地推任务 |
| LM-11 | 新建任务（无线长） | toast「暂无线长，请先创建线长账号」· 不弹层 |
| LM-12 | `?tab=withdraws` | **FINDING**：URL 保留 query，Tab 仍停在「线长成员」 |
| LM-13 | 刷新 | 可点 |
| LM-U-01 | 900 | 无整页横滚 |
| LM-U-03 | 结束 | 1366×768 DPR≈1 |

---

## 3. 结案清单

- [x] 成员/提现/地推三空态诚实 ↔ API total=0
- [x] 新建线长取消；新建任务无线长门控 toast
- [x] 状态筛中文；批量审批 disabled
- [x] 行调账/绑柜/审核 SKIP（无数据）
- [x] FINDING：`?tab=` 未同步（见 FINDINGS）
- [x] BUTTONS / FINDINGS / Changelog；FINANCE_MERCHANT 深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 列表 | 成员共 **0** · 提现共 **0** · 地推空 |
| 打款 | 记账模式 Alert（非真实转账） |
| 写路径 | 新建取消 · 地推门控 toast · 行写 SKIP |
| 深链 | `?tab=` **未切 Tab**（FINDING） |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
