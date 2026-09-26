# 财务毛利 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`FINANCE_FULL_BROWSER_UAT.md`](../../../uat/FINANCE_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0** · 按钮清点 100%

KPI×6 / 累计快照 / TOP1 SKU 与 `GET …/finance/report?days=1` 一致；固化昨日毛利有中文二次确认并可取消；days 与 URL 同步；非法 days 回退并 toast。

## KPI vs API（days=1）

| 项 | UI | API |
|----|-----|-----|
| 今日营收 | ¥3.50 | 350¢ |
| 今日成本 | ¥1.90 | 190¢ |
| 今日毛利 | ¥1.60 | 160¢ |
| 毛利率 | 45.7% | 0.457… |
| 订单 | 2 | 2 |
| 客单 | ¥1.75 | 175¢ |
| 累计营收 | ¥33.50 | 3350¢ |
| TOP SKU | 可口可乐 330ml · 毛利¥1.60 | SKU-DEMO-001 |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 商户端无「财务毛利」同页；三端对账沿用历史造单 **今日营收 350¢**，与本页 KPI 对齐。本轮商户 H5 落在登录页（未带会话），未再强登。 |

## UX

- 900 视口：`docW === clientW === 900`，无整页横滚。
- 固化按钮有 confirm（优于温控/套用模板无确认页）。

## 证据

`fin-01-home` · `fin-02-refresh` · `fin-03-solidify-confirm` · `fin-04-days` · `fin-05-chart` · `fin-06-table` · `fin-07-days7` · `fin-08-cols` · `fin-ux-*` · `fin-api-1d.json` · `fin-99-end`
|
