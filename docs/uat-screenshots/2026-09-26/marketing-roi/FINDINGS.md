# 活动效果分析 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`MARKETING_ROI_FULL_BROWSER_UAT.md`](../../../uat/MARKETING_ROI_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0**

默认近 30 天共 **0**，「暂无活动数据」诚实，与 `GET /api/v2/ops/admin/growth/marketing-roi?days=30`（及 7/90）一致。hint 区分「预算已用」与「订单优惠」。关键词无匹配仍空；重置清空。近 7/90 切档发请求。空表点导出 → toast「暂无数据可导出」（非下载）。刷新可点。窄视口 900 无横滚；结束 **1366×768 DPR=1**。本页无硬写。

## 数据对照

| 面 | UI / API |
|----|----------|
| 近 30 天 | 共 **0** · `len=0` |
| 近 7 天 | 共 **0** · `len=0` |
| 近 90 天 | 共 **0** · `len=0` |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 列表接口无分页：整表拉取后关键词前端过滤 + 切片。 |
| 2 | 空表导出走 `useListCsv` 警告「暂无数据可导出」，不落文件——预期。 |
| 3 | 金额列用分→元 `toFixed(2)`（无 `¥` 前缀）；核销率 `xx.x%`。 |
| 4 | 与 [`PROMOTIONS_FULL_BROWSER_UAT.md`](../../../uat/PROMOTIONS_FULL_BROWSER_UAT.md) 空态一致：本机无营销活动则 ROI 无行。有活动后须补测有数导出与类型中文。 |
| 5 | 类型走 dict `promotion_type`；状态 `enable_status`。 |

## 证据

`roi-01`…`roi-07` · `roi-ux-*` · `roi-99-end`
|
