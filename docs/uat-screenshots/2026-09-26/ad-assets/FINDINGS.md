# 素材库 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`AD_ASSETS_FULL_BROWSER_UAT.md`](../../../uat/AD_ASSETS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 1 · BLOCK 0**

列表共 **0**，与 `GET /api/v2/ops/admin/ad/assets` 一致。hint 明示柜机播放/曝光未接、仅后台预览。上传：stub `fileInput.click` 打开面板（类型图片/视频/H5）→**取消**，未真传文件。编辑/删除/批删因无数据 SKIP。导出/刷新可点。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI / API |
|----|----------|
| 全部 | 共 **0** |

## 本轮缺陷

| ID | 严重度 | 现象 | 根因 | 必须怎么做 |
|----|--------|------|------|------------|
| FINDING-1 | 中 | 「批量停用」无二次确认，勾选后一点即 PUT `INACTIVE` | `batchDeactivate()` 无 `ElMessageBox`（删有确认、停用没有） | 有数据时**禁止**在 UAT 点批量停用；产品应补确认框，与批量删除对齐 |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 点「上传素材」会 `fileInput.click()` → OS 文件框；UAT 用 stub 或 SKIP，禁止真选文件。 |
| 2 | 关键词为**前端过滤**；`total` 仍取服务端（源码注释）——有数据时可能「共 N 行空」，需防假绿。 |
| 3 | CrudTable 未设 `empty-text`，空态依赖分页「共 0」/默认空表。 |
| 4 | 有素材时须补测：编辑取消、删除确认取消、批量删除确认取消；批量停用待修确认后再测。 |

## 证据

`aa-01`…`aa-05` · `aa-10`/`aa-11` · `aa-ux-*` · `aa-99-end`
|
