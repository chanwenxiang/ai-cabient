# 异常中心 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`EXCEPTIONS_FULL_BROWSER_UAT.md`](../../../uat/EXCEPTIONS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**（含 #207 修复后复测）

OPEN/全部/已解决与 API 对齐；抽屉五类写路径确认取消；开门记录/争议/设备/设备运维深链通过。结束视口 **1366×768**。

## 本轮修复

| # | 现象 | 根因 | 修复 |
|---|------|------|------|
| 207 | 点「全部」后 Tab 仍「待处理 (12)」，total≠111 | `syncRouteQuery` 省略 `status` → `applyRouteQuery` 缺省回落 `OPEN` | 「全部」显式写 `status=ALL`；`applyRouteQuery` 对 status 做 `toUpperCase` |

## 数据对照

| 项 | UI | API `GET /api/v2/ops/admin/exceptions` |
|----|-----|----------------------------------------|
| OPEN | 共 12 条 · 待处理 | total=12 |
| 全部 | 共 **111** 条 · Tab「全部」· `?status=ALL` | 无 status → total=111 |
| 已解决 | 共 99 条 | total=99 |
| 仅超时（OPEN） | 共 12 条 | overdue=1 → total=12 |

## 视口

主测 MCP `browser_resize` 钉 1366；窄 900 / 宽 1920 抽检后恢复。窄屏无整页横滚。见 lessons #206。

## UAT 操作注意（非产品 FAIL）

| # | 说明 |
|---|------|
| 1 | API 路径是 `/api/v2/ops/admin/exceptions`，curl 错前缀会 404。 |
| 2 | 深链「开门记录」在抽屉；列表行「设备」进设备详情。 |
| 3 | MessageBox 须等 overlay opacity 就绪再点取消（#200）。 |
| 4 | 「全部」验收必须断言 URL 含 `status=ALL` 且 total=无筛选 API。 |

## 证据

`exc-01`…`exc-14` · `exc-02b-all-direct` · `exc-ux-*` · `exc-api-list.json` · `exc-99-end`
|
