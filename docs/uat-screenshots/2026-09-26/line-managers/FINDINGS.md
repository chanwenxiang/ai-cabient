# 线长钱包 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`LINE_MANAGERS_FULL_BROWSER_UAT.md`](../../../uat/LINE_MANAGERS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 1 · BLOCK 0**

本机线长成员 / 提现 / 地推均为空态，中文空文案诚实。记账打款 Alert 与后端 payout-mode 一致。新建线长弹层取消不落库；无线长时「新建任务」toast 门控正确。提现批量通过/驳回无勾选 disabled。行级调账/绑柜/审核因无数据 SKIP。结束视口 **1366×768**。

## 数据对照

| 面 | UI | API / 行为 |
|----|-----|------------|
| 线长成员 | 共 **0** · 暂无线长 | `GET …/line-managers` total=0 |
| 提现审核 | 共 **0** · 暂无提现申请 | 空表 |
| 地推任务 | 暂无地推任务 | 空表 |
| payout-mode | 记账打款 · fee 0 | Alert warning |
| 新建任务 | toast「暂无线长，请先创建线长账号」 | `openPromoCreate` 门控 |

## 本轮缺陷

| # | 严重度 | 现象 | 根因 | 建议 |
|---|--------|------|------|------|
| F-01 | P2 | `?tab=withdraws` / `?tab=promo` 打开后仍停在「线长成员」 | `tab` 初始写死 `managers`，未读 `route.query.tab`；仅代提现成功后代码内切 Tab | `onMounted`/`watch route` 同步合法 `tab`；可选 `onTab` 回写 query |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **记账打款**：审核通过只改状态，不向微信转账到零钱（与 Alert 一致）。 |
| 2 | 批量通过/驳回仅 `ops:line-withdraw:review` 且在提现 Tab 显示；无勾选 disabled。 |
| 3 | 无线长时禁止开地推弹层（toast），属产品门控，非缺陷。 |
| 4 | 行「调账 / 代提现 / 绑柜 / 通过并打款」须有数据；空表合法 SKIP。 |

## 证据

`lm-01`…`lm-13` · `lm-08c` · `lm-11-promo-gate` · `lm-ux-*` · `lm-99-end`
|
