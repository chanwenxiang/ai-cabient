# 积分兑换管理 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`POINTS_REDEEM_FULL_BROWSER_UAT.md`](../../../uat/POINTS_REDEEM_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 1 · BLOCK 0**

列表共 **0**，「暂无兑换项」诚实，与 `GET /api/v2/ops/admin/growth/points-redeem` 一致。hint 明示兑换后自动发券并扣积分。新建兑换项弹层→**取消**。编辑/行启停/批上下架因无数据 SKIP。导出/刷新可点。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI / API |
|----|----------|
| 全部 | 共 **0** |

## 本轮缺陷

| ID | 严重度 | 现象 | 根因 | 必须怎么做 |
|----|--------|------|------|------------|
| FINDING-1 | 中 | 行「启用/停用」无二次确认，一点即 POST status | `toggleStatus()` 无 `ElMessageBox`（批量上架/下架有确认） | 有数据时 UAT **禁止**盲点行启停；产品应补确认 |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 列表接口无分页：整表拉取后前端过滤+切片；关键词亦前端过滤。 |
| 2 | 保存用 PUT 兼新建/更新（`itemId` 可空）。 |
| 3 | 兑换依赖券定义下拉；本机优惠券有数据时新建可选券。 |
| 4 | 有数据时须补测：编辑取消、批上下架确认取消；行启停待补确认后再测。 |
| 5 | C 端积分商城：见 [`MINIPROGRAM_BACKLOG.md`](../../../uat/MINIPROGRAM_BACKLOG.md)。 |

## 证据

`pr-01`…`pr-05` · `pr-09`/`pr-10` · `pr-ux-*` · `pr-99-end`
|
