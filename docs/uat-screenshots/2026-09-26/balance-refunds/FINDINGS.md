# 余额退款 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`BALANCE_REFUNDS_FULL_BROWSER_UAT.md`](../../../uat/BALANCE_REFUNDS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0**

五状态 Tab（待审核/已退款/已驳回/失败/全部）均为共 **0**，「暂无申请」诚实，与 API 一致。批量通过/驳回无勾选 disabled。行级通过/驳回、用户深链因无数据 SKIP。结束视口 **1366×768**。

## 数据对照

| Tab | UI | API |
|-----|-----|-----|
| PENDING_REVIEW | 共 0 · 暂无申请 | total=0 |
| REFUNDED / REJECTED / FAILED / ALL | 共 0 | total=0 |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 关键词为**前端过滤**；`total` 仍取服务端分页 total。本机全空未触发「行空 total 非 0」假绿；有数据环境需再验（代码 `fetchPage` 返回 `total: res.total`）。 |
| 2 | 默认 Tab 存 `localStorage ops_balance_refund_status_tab`（默认待审核）。 |
| 3 | 通过 = 原路退微信/支付宝并扣余额；驳回 = 释放冻结；须二次 prompt。 |
| 4 | 有待审数据时须补测：通过/驳回 → 取消；用户链 → `/users`。 |
| 5 | 小程序回补：消费者余额变动见 [`MINIPROGRAM_BACKLOG.md`](../../../uat/MINIPROGRAM_BACKLOG.md) §1 #4。 |

## 证据

`br-01`…`br-09` · `br-04*` · `br-ux-*` · `br-99-end`
|
