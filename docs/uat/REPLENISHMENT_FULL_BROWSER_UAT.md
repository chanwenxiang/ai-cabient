# 补货调度 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「履约仓储 → 补货调度」**单页执行真源**。比 [`WAREHOUSE_FULL_BROWSER_UAT.md`](./WAREHOUSE_FULL_BROWSER_UAT.md) 该项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。写路径仅确认→取消（规划路线 / 取消空路线 / 要货审批）。  
> **源码**：`clients/admin-vue/src/views/replenishment/ReplenishmentView.vue`  
> **API**：`/replenishment/summary|routes|fulfillment-tasks|requests|shortage`；临期 `expiry/alerts`  
> **视口**：主测 **1366×768**（DPR=1）。  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/replenishment/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/replenishment/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/replenishment/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| KPI | 待执行 / 待处理设备 / 已履约 / 要货待审 / 临期（↔ summary） |
| 头 | 规划补货路线 · 导出 |
| Tab | 补货路线 · 履约记录 · 商户要货 · 缺货建议 · 临期下架 |

---

## 2. 用例（REP-*）

| ID | 步骤 | 期望 |
|----|------|------|
| REP-01 | 打开 `/replenishment` | 标题；路线共 N；KPI↔summary；状态中文 |
| REP-02 | 展开路线 | 任务/签到中文 |
| REP-03 | 规划补货路线 → 取消 | 弹层关闭不落库 |
| REP-04 | 取消空路线 → 取消 | 确认框 dismiss |
| REP-05 | Tab 履约记录 | `tab=fulfillment`；仅待分配筛 |
| REP-06 | Tab 商户要货 | 默认待审核空；全部/已接单有数 |
| REP-07 | 要货审批流 | 抽屉；已接单→查看补货任务 |
| REP-08 | Tab 缺货建议 | 共 N↔API；一键规划 |
| REP-09 | 一键规划 → 取消 | 不落库 |
| REP-10 | 缺货行设备 | → `/devices/{id}` |
| REP-11 | Tab 临期下架 | `tab=expiry` |
| REP-12 | `?plan=1&deviceIds=` | 弹「规划补货路线」再取消（#203） |
| REP-13 | `?deviceId=` | 设备 chip 聚焦 |
| REP-15 | KPI | 与 summary 四数一致 |
| REP-U-01 | 900 | 无整页横滚 |

---

## 3. 结案清单

- [x] KPI 待执行3/待处理0/已履约3/要货待审0；路线共 6；缺货共 9
- [x] 规划弹层×3 路径取消；取消空路线确认取消；#203 深链弹层
- [x] 要货默认待审核空；全部/已接单共 1；审批流抽屉
- [x] BUTTONS / FINDINGS / Changelog；WAREHOUSE 深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| KPI/路线 | summary 对齐 · 路线共 **6** · 状态中文 |
| 缺货 | 共 **9** · 一键规划可开取消 |
| 要货 | 默认待审核 0；全部 **1** 已接单 |
| 深链 | plan=1 弹层 · deviceId chip |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
