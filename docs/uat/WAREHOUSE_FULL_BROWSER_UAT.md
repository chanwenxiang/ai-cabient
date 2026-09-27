# 运营后台 ·「履约仓储」全量浏览器 UAT（Phase 4）

> **地位**：第四册（补货 / 仓库 / OTA / SLA / 补货员效率）。工具铁律同 Overview §1。  
> **版本**：1.1 · 2026-09-26（侧栏五页复测）  
> **截图**：`docs/uat-screenshots/2026-09-26/warehouse/`

| 序 | 路径 | 标题 |
|----|------|------|
| 1 | `/replenishment` | 补货调度 |
| 2 | `/warehouse` | 仓库 |
| 3 | `/ota` | 固件版本 |
| 4 | `/sla` | 服务时限监控 |
| 5 | `/replenishment-staff` | 补货员效率 |

| 字段 | 值 |
|------|-----|
| 结案 | **Phase 4 Done（复测）**；[`warehouse/BUTTONS.md`](../uat-screenshots/2026-09-26/warehouse/BUTTONS.md) + [`FINDINGS.md`](../uat-screenshots/2026-09-26/warehouse/FINDINGS.md) |

ID：`WH-REP-*` · `WH-WH-*` · `WH-OTA-*` · `WH-SLA-*` · `WH-STAFF-*`

| ID | 期望 |
|----|------|
| WH-REP-01 | 路线/履约 Tab 有数或中文空态 |
| WH-REP-02 | 补货调度深测：见 [`REPLENISHMENT_FULL_BROWSER_UAT.md`](./REPLENISHMENT_FULL_BROWSER_UAT.md)（KPI/五Tab/规划取消/#203） |
| WH-WH-01 | 仓库列表可开；采购/库存/履约分组可切 |
| WH-WH-02 | 仓库单页深测：见 [`WAREHOUSE_VIEW_FULL_BROWSER_UAT.md`](./WAREHOUSE_VIEW_FULL_BROWSER_UAT.md)（四分组/新建取消/在途深链） |
| WH-OTA-01 | 无版本时诚实空态 |
| WH-OTA-02 | 固件版本单页深测：见 [`OTA_FULL_BROWSER_UAT.md`](./OTA_FULL_BROWSER_UAT.md)（双空态/发布取消/进度筛） |
| WH-SLA-01 | 在线率仅投放柜；开门时长可读（非裸超大 ms） |
| WH-SLA-02 | 服务时限单页深测：见 [`SLA_FULL_BROWSER_UAT.md`](./SLA_FULL_BROWSER_UAT.md)（8KPI↔API/实时/刷新） |
| WH-STAFF-01 | 补货员效率列表/导出 |
| WH-STAFF-02 | 补货员效率单页深测：见 [`REPLENISHMENT_STAFF_FULL_BROWSER_UAT.md`](./REPLENISHMENT_STAFF_FULL_BROWSER_UAT.md)（7/30/90/关键词total/导出） |

执行日志：`docs/uat-screenshots/2026-09-26/warehouse/FINDINGS.md`  
按钮清点：`docs/uat-screenshots/2026-09-26/warehouse/BUTTONS.md`

## Done

- [x] 关键 ID 均有 PASS + 证据  
- [x] 写路径确认后取消（#200）  
- [x] **2026-09-26 复测**：附录 B 编号 + `wh-*.png`；含补货员效率  
