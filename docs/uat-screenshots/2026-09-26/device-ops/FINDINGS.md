# 设备运维 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`DEVICE_OPS_FULL_BROWSER_UAT.md`](../../../uat/DEVICE_OPS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**（含 #208 修复后复测）

事件列表 total=69↔API；类型/级别/设备筛选与中文标签正常；导出/升降序/刷新/列设置通过。本页无锁机写路径（锁机在设备详情）。结束视口 **1366×768**。

## 本轮修复

| # | 现象 | 根因 | 修复 |
|---|------|------|------|
| 208 | 关键词滤空仍显示「共 69 条」 | 关键词仅前端本页 `filterByKeyword`，`total` 仍用服务端全量（同 #205） | 本页有剔除时 `total=filtered.length`；全保留才用服务端 total |

## 数据对照

| 项 | UI | API |
|----|-----|-----|
| 全量 | 共 69 | `GET …/device-ops/events` total=69 |
| 类型=离线 | 共 29 | eventType 过滤 |
| 级别=警告 | 共 39 | severity=WARN |
| 设备 7777… | 共 40 | deviceId |
| 关键词无匹配 | 共 **0** | 前端过滤 |

## UAT 操作注意（非产品 FAIL）

| # | 说明 |
|---|------|
| 1 | EP Select 选项 class 是 `.el-select-dropdown__item`；须用 `aria-controls` 对应 listbox，勿扫错 popper。 |
| 2 | 点 `role=combobox` 的 input 易被 placeholder 挡住 → 点 `.el-select__wrapper`。 |
| 3 | API：`/api/v2/ops/admin/device-ops/events`；设备筛依赖 `/devices/ref`。 |
| 4 | 关键词为**本页**过滤，跨页全文检索未做；UAT 须断言空表↔共 0。 |

## 证据

`do-01`…`do-09` · `do-ux-*` · `do-api-list.json` · `do-99-end`
|
