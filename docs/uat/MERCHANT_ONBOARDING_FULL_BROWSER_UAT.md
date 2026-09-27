# 进件工作台 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「财务商户 → 进件工作台」**单页执行真源**。比 [`FINANCE_MERCHANT_FULL_BROWSER_UAT.md`](./FINANCE_MERCHANT_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。写路径仅确认→取消（新建/编辑/审批）。  
> **源码**：`clients/admin-vue/src/views/merchants/MerchantOnboardingView.vue`  
> **API**：`GET/POST …/merchant-onboarding` · `…/live-hints` · `…/{id}/review`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/merchant-onboarding/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/merchant-onboarding/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/merchant-onboarding/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 标题 · hint「仅登记…不推送」· 批量通过/驳回 · 新建进件 |
| Alert | live-hints：仅登记 / 微信·支付宝·支付分 正式|测试 |
| 筛选 | 商户编号 · 渠道 · 状态 · 查询 |
| 表 | CrudTable：商户/渠道/状态/外部号/支付模式/备注/时间 · 导出/刷新 |

---

## 2. 用例（ONB-*）

| ID | 步骤 | 期望 |
|----|------|------|
| ONB-01 | 打开 `/merchant-onboarding` | 共 N↔API；仅登记 hint；空态诚实 |
| ONB-02 | 新建进件 → 取消 | 不落库 |
| ONB-03 | 渠道下拉 | 微信/支付宝/支付分（无 WECHAT 裸码） |
| ONB-04 | 筛微信 | total↔API |
| ONB-05 | 状态下拉 | 草稿/已提交/已生效/已驳回 |
| ONB-06 | 筛已提交 | total↔API |
| ONB-07 | 商户编号无匹配 | 共 0 |
| ONB-08 | 批量通过/驳回 | 无勾选时 disabled |
| ONB-09 | 空表点保存 | toast「请填写商户与渠道」 |
| ONB-10 | 行编辑/通过/驳回 | 有行则确认→取消；本机空表 SKIP |
| ONB-U-01 | 900 | 无整页横滚 |

---

## 3. 结案清单

- [x] 共 **0** 空态「暂无进件记录」↔ API；仅登记 live-hints
- [x] 新建取消；校验 toast；渠道/状态中文筛
- [x] 批量审批门控 disabled；行审批 SKIP（无数据）
- [x] BUTTONS / FINDINGS / Changelog；FINANCE_MERCHANT 册深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **0** · 暂无进件记录 |
| 模式 | 仅登记 · 微信/支付宝/支付分均为**测试** |
| 写路径 | 新建取消 · 空保存 toast · 行审批 SKIP |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
