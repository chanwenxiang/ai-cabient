# 优惠券 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`COUPONS_FULL_BROWSER_UAT.md`](../../../uat/COUPONS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0**

列表共 **1**，与 `GET /api/v2/coupons/definitions` 一致（「完整轮满减券」· `AMOUNT_OFF` · ¥0.50 · 发行 3/20 · 启用）。状态下拉中文。新建 / 手动发券 / 批量发券 / 编辑 / 行发券 / 更多→停用 / 批量停用一律**取消**未提交。`?keyword=完整` 深链 OK。导出与下载模板成功；导入仅验可见。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI / API |
|----|----------|
| 全部 | 共 **1** |
| 样例 | id=1 · 完整轮满减券 · denomCents=50 · ACTIVE · 3/20 |
| 关键词「完整」 | 共 **1** |
| 无匹配 | 共 **0** · 暂无优惠券 |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | API 真源 `/api/v2/coupons/definitions`（非 `/ops/coupons`）。 |
| 2 | **停用**在操作列「更多」下拉（`action-width=100` 溢出），非直接图标。 |
| 3 | 手动/批量发券点「发放」后还有二次 MessageBox——本轮在弹层取消，未进二次确认。 |
| 4 | 导入唤起系统文件框 → UAT **不点**。 |
| 5 | C 端券包可见性：见 [`MINIPROGRAM_BACKLOG.md`](../../../uat/MINIPROGRAM_BACKLOG.md)。 |

## 证据

`cpn-01`…`cpn-16` · `cpn-ux-*` · `cpn-99-end`
|
