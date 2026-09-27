# 投放计划 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`AD_CAMPAIGNS_FULL_BROWSER_UAT.md`](../../../uat/AD_CAMPAIGNS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 1 · BLOCK 0**

列表共 **0**，「暂无投放计划」诚实，与 `GET /api/v2/ops/admin/ad/campaigns` 一致。hint 说明小程序开门页可拉素材并回传曝光/完播/点击。新建投放弹层（范围全部/指定、时间窗、轮播素材）→**取消**。编辑/上线/停止/删除/批停因无数据 SKIP。导出/刷新可点。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI / API |
|----|----------|
| 全部 | 共 **0** |

## 本轮缺陷

| ID | 严重度 | 现象 | 根因 | 必须怎么做 |
|----|--------|------|------|------------|
| FINDING-1 | 中 | 行「上线」「停止」无二次确认，一点即 POST launch/stop | `launch()`/`stop()` 无 `ElMessageBox`（批量停止/删除有确认） | 有数据时 UAT **禁止**盲点上线/停止；产品应补确认，与批停对齐 |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 关键词前端过滤（当前页）；`total` 取服务端——有数据时防假绿。 |
| 2 | 保存须至少选一个素材；本机素材库亦空，真建投放需先有素材。 |
| 3 | 删除仅非 RUNNING；RUNNING 只能停止。 |
| 4 | 有数据时须补测：编辑取消、删除确认取消、批量停止确认取消；上线/停止待补确认后再测。 |
| 5 | C 端开门页曝光：见 [`MINIPROGRAM_BACKLOG.md`](../../../uat/MINIPROGRAM_BACKLOG.md)。 |

## 证据

`ac-01`…`ac-05` · `ac-10`/`ac-11` · `ac-ux-*` · `ac-99-end`
|
