# 营销活动 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`PROMOTIONS_FULL_BROWSER_UAT.md`](../../../uat/PROMOTIONS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0**

列表共 **0**，「暂无活动」诚实，与 `GET /api/v2/ops/promotions` total=0 一致。页头 hint 明示：预算用尽后仍可「启用」，发券拦截并显示「预算已满」。状态下拉中文（启用/停用）。新建活动弹层→**取消**。编辑/启停/批停因无数据 SKIP。`?keyword=` 深链填入输入框。导出 / 下载模板成功；**导入**仅验可见、未点文件框。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI / API |
|----|----------|
| 全部 | 共 **0** · `/api/v2/ops/promotions` code=0 total=0 |
| 启用 / 停用筛 | 共 **0** |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | API 真源为 `/api/v2/ops/promotions`（**非** `/ops/admin/promotions`）。 |
| 2 | **软写**：新建仅测到弹层取消；有数据时须补测编辑取消、启停确认取消、批量停用确认取消。 |
| 3 | 导入会唤起系统文件框 → UAT **不点**（同素材上传）。 |
| 4 | 预算已满 Tag 为派生态（启用且 used≥budget）——本机空未触发，有数据时目视。 |
| 5 | C 端活动可见性：见 [`MINIPROGRAM_BACKLOG.md`](../../../uat/MINIPROGRAM_BACKLOG.md)。 |

## 证据

`pr-01`…`pr-07` · `pr-11`…`pr-14` · `pr-ux-*` · `pr-99-end`
|
