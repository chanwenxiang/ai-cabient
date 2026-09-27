# 充值管理 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「财务商户 → 充值管理」**单页执行真源**。比 [`FINANCE_MERCHANT_FULL_BROWSER_UAT.md`](./FINANCE_MERCHANT_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。退款仅 prompt→取消。  
> **源码**：`clients/admin-vue/src/views/recharges/RechargeListView.vue`  
> **API**：`GET …/recharges` · `POST …/recharge/{orderId}/refund`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/recharges/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/recharges/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/recharges/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · FINDING 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 标题「充值管理」· hint（状态/用户筛；金额居中） |
| 筛 | 关键词（用户编号正整数）· 状态 · 查询 · 重置 |
| 表 | 充值单/用户/金额¥/渠道/外部单号/状态/创建·支付·退款时间 · 退款（仅已支付）· 导出/刷新 |

---

## 2. 用例（RCH-*）

| ID | 步骤 | 期望 |
|----|------|------|
| RCH-01 | 打开 `/recharges` | 共 N↔API；¥；中文状态 |
| RCH-02 | 状态下拉 | 已创建/待支付/已支付/成功/失败/已退款/已取消/已关闭 |
| RCH-03 | 筛已支付 | 共↔API · URL `status=PAID` |
| RCH-04 | 关键词非整数 | toast「用户编号须为正整数」 |
| RCH-05 | userId 无匹配 | 共 0 · 暂无充值记录 |
| RCH-06 | 退款 → 取消 | MessageBox prompt 不落库 |
| RCH-07 | `?status=PAID` | 筛选项「已支付」· 共 2 |
| RCH-08 | 导出 | CSV |
| RCH-09 | 刷新 | 可点 |
| RCH-U-01 | 900 | 无整页横滚 |
| RCH-U-03 | 结束 | 1366×768 DPR≈1 |

---

## 3. 结案清单

- [x] 共 3↔API；状态筛/深链有效；非法关键词 toast
- [x] 退款取消；导出 CSV
- [x] BUTTONS / FINDINGS / Changelog；FINANCE_MERCHANT 深链
- [x] **MP 回补**（2026-09-27）：¥193.00 ↔ Admin；见 [`../uat-screenshots/2026-09-26/recharges/MP_BACKFILL.md`](../uat-screenshots/2026-09-26/recharges/MP_BACKFILL.md)

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **3**（PAID×2 · CANCELLED×1）· ¥20.00 |
| 筛 | 已支付→2；userId 999999999→0 |
| 写路径 | 退款 prompt 取消 |
| 深链 | `?status=PAID` 生效 |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
