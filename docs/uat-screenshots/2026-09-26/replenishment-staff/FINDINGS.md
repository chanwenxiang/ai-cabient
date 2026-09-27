# 补货员效率 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`REPLENISHMENT_STAFF_FULL_BROWSER_UAT.md`](../../../uat/REPLENISHMENT_STAFF_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

默认近 30 天共 **1** 人（默认商户管理员，完成率 60.0%）；近 7 天诚实空态；关键词前端过滤且 **total=过滤后长度**（无 #208 假绿）。导出 CSV 成功。本页无硬写。结束视口 **1366×768**。

## 数据对照

| 筛 | UI | API `…/replenishment-report/staff?days=` |
|----|-----|-----|
| 默认 days=30 | 共 **1** · 任务5/完成3/60.0%/待办2 | len=1 · completionRate=0.6 |
| days=7 | 共 **0** · 暂无补货任务数据 | len=0 |
| days=90 | 共 **1** | len=1 |
| 关键词命中 | 共 1 | 前端滤 |
| 关键词无匹配 | 共 **0** | 前端滤 · total=0 |

样例行：`userId=100000030` · 姓名「默认商户管理员」· 手机 13800138001 · avgDailyTasks=0.17

## 本轮缺陷

无 FAIL。无需改码。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 接口**无分页、无 q**；关键词姓名纯前端过滤；`fetchPage` 已 `total=list.length`，滤空不假绿。 |
| 2 | 「重置」只清关键词，**不**把天数拨回 30（与查询体验一致：天数由 radio 独立控制）。 |
| 3 | 「已完成」列文案来自 `displayLabel('order_status','COMPLETED')`，验收禁英文码。 |
| 4 | 待办 `openTasks>0` 用 `.cell-warn` 高亮；scoped 内有裸 hex（`#b45309`），属 token 债，非本轮功能 FAIL。 |
| 5 | 可勾选但无批量写操作；导出权限 `ops:replenishment:export`。 |
| 6 | 近 7 天空表是「窗口内无任务」不是接口坏。 |

## 证据

`rs-01`…`rs-10` · `rs-ux-*` · `rs-99-end` · `rs-api-probe.json`
|
