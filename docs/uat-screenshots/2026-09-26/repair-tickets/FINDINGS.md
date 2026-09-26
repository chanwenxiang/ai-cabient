# 维修工单 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`REPAIR_TICKETS_FULL_BROWSER_UAT.md`](../../../uat/REPAIR_TICKETS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

列表与 `repair-tickets` API 对齐；状态/设备/故障/优先级筛选正确；待处理空态诚实；`?deviceId=` 深链回显；详情抽屉含流转记录中文；新建与批量指派均取消未落库。结束视口 **1366×768**。

## 数据对照

| 筛 | UI | API |
|----|-----|-----|
| 全部 | 共 1 | total=1 |
| status=DONE | 共 1 | total=1 |
| status=OPEN | 共 0 · 暂无维修工单 | total=0 |
| deviceId=777740024057 | 共 1 | total=1 |
| faultType=DOOR | 共 1 | total=1 |
| priority=NORMAL | 共 1 | total=1 |
| 详情 #1 | 流转 3 条 | events.length=3 |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **本环境仅 1 张 DONE 工单**：行操作无「开始处理/完成/取消」；流转写路径无法软测，记 SKIP 而非 FAIL。 |
| 2 | **批量指派**未勾选时 disabled；勾选后弹层「已选 N 张未关闭工单」——DONE 仍可勾选打开弹层（产品现状），本轮取消未提交。 |
| 3 | 故障「门锁」= API `DOOR`；优先级「普通」=`NORMAL`；状态「已完成」=`DONE`。 |

## UAT 操作注意

| # | 说明 |
|---|------|
| 1 | EP Select 选项 class 是 **`.el-select-dropdown__item` / `[role=option]`**，不是 `.el-option`；须用 `aria-controls` 定位 listbox 再点。 |
| 2 | 会话过期后登录体字段是 `phoneNumber`（非 phone）。 |

## 证据

`rep-01`…`rep-14` · `rep-ux-*` · `rep-99-end`
|
