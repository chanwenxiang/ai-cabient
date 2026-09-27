# 补货调度 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`REPLENISHMENT_FULL_BROWSER_UAT.md`](../../../uat/REPLENISHMENT_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

KPI↔summary；五 Tab 可切；规划弹层（头/缺货/深链 #203）均取消；取消空路线确认 dismiss。要货默认「待审核」空、切「全部」见已接单。结束视口 **1366×768**。

## 数据对照

| 项 | UI | API |
|----|-----|-----|
| KPI | 待执行3 / 待处理0 / 已履约3 / 要货待审0 | summary 同 |
| 路线 | 共 6 | routes total=6 |
| 缺货 | 共 9 | shortage total=9 |
| 要货·待审核 | 共 0 | status=SUBMITTED → 0 |
| 要货·全部 | 共 1 已接单 | status=ALL total=1（ACCEPTED） |
| 临期 | 共 0 | expiry total=0 |

## 本轮缺陷

无 FAIL。无需改码。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 要货默认筛 **SUBMITTED（待审核）**；断言全量须点「全部」或「已接单」，勿与 bare API total 直接比。 |
| 2 | 深链 `?plan=1&deviceIds=` 须先弹「规划补货路线」再 `syncRouteQuery` 清参（lesson **#203**）；本轮弹层可见后取消。 |
| 3 | 「取消空路线」确认主按钮文案为「确认取消」；自动化 dismiss 须 `name: '取消', exact: true`。 |
| 4 | 已接单要货抽屉为「查看补货任务」，无同意/驳回（审批已完成）。 |
| 5 | 缺货行设备链可用 `element.click()`（表区滚动时 Playwright 可见性点击可能超时）。 |

## 证据

`rep-01`…`rep-13` · `rep-ux-*` · `rep-99-end`
|
