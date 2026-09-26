# 客流坪效 · FINDINGS · 2026-09-26

> 与 [`BUTTONS.md`](./BUTTONS.md) / [`FOOTFALL_FULL_BROWSER_UAT.md`](../../../uat/FOOTFALL_FULL_BROWSER_UAT.md) 配套。

## 结论

**PASS · FAIL 0 · BLOCK 0** · 口径说明 1（三端时间窗）

客流坪效页：刷新、近 7/30/90、KPI×6 对齐 API、柜机/商品金额 `¥`、转化「—」语义、货道下拉、窄视口无整页横滚均通过。

## 口径说明

| # | 现象 | 说明 |
|---|------|------|
| 1 | 商户 mp 仅有「今日」首页 KPI，无客流坪效同页 | 后台 footfall 为近 N 天运营窗；三端只核对金额样例（今日 ¥3.50），不强行对齐 7 日开门/订单总数 |

## API / UI 快照（近 7 天）

| 字段 | 值 |
|------|-----|
| totalOpens | 29 |
| totalPaidOrders | 10 |
| conversionRate | 34.48% → UI 34.5% |
| avgOrderValueCents | 270 → ¥2.70 |
| deviceCount | 3 |
| 柜机行 | 3304… 29开/9单；7777… 0开/1单→「—」；CAB-001 全 0 |

## 证据

`ff-01-home.png` · `ff-02-days.png` · `ff-03-refresh.png` · `ff-04-narrow.png` · `ff-05-slots.png` · `ff-ux-*.png` · `ff-api-7d.json` · `ff-mp-m-home.png` · `ff-three-end.json`
|
