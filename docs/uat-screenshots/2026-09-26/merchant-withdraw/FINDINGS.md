# 商户提现 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`MERCHANT_WITHDRAW_FULL_BROWSER_UAT.md`](../../../uat/MERCHANT_WITHDRAW_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 1 · BLOCK 0**

商户钱包共 **2**、提现共 **1**（已打款）与 API 一致。记账打款 Alert 正确。调账 / 代提现弹层取消不落库；流水抽屉可开。提现状态筛中文；批量通过/驳回无勾选 disabled。终态行无审核按钮（合法 SKIP）。结束视口 **1366×768**。

## 数据对照

| 面 | UI | API |
|----|-----|-----|
| 商户钱包 | 共 **2** · MCH-DEFAULT ¥29.15 · MCH-OTHER ¥0.00 | total=2 |
| 关键词无匹配 | 共 0 · 暂无商户钱包 | — |
| 提现默认 | 共 **1** · 已打款 · ¥1.00 · fee 0 | total=1 · PAID |
| 筛已打款 | 共 1 · MW-FULL-… | — |
| payout-mode | 记账打款 · fee 0 | mockEnabled=true |

## 本轮缺陷

| # | 严重度 | 现象 | 根因 | 建议 |
|---|--------|------|------|------|
| F-01 | P2 | `?tab=withdraws` 打开后仍停在「商户钱包」 | `tab` 初始写死 `wallets`，未读 `route.query.tab`（与线长钱包同型） | `onMounted`/`watch route` 同步合法 tab；可选 `onTab` 回写 query |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **记账打款**：审核通过只改状态，不向微信发起转账。 |
| 2 | 通道列 `MOCK` 字典展示为「其他」（`pay_channel`）；回执文案仍明示 Mock。 |
| 3 | 「代提现」收在更多菜单（操作列宽约 130）；图标按钮靠 aria-label。 |
| 4 | 批量通过/驳回仅提现 Tab + 审核权限；无勾选 disabled。 |
| 5 | 行「通过并打款」仅 `PENDING_REVIEW`；本机仅 PAID → SKIP。 |

## 证据

`mw-01`…`mw-12` · `mw-05*` · `mw-07b` · `mw-ux-*` · `mw-99-end`
|
