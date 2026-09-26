# 维修工单 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「设备商品 → 维修工单」**单页执行真源**。比 [`DEVICE_SKU_FULL_BROWSER_UAT.md`](./DEVICE_SKU_FULL_BROWSER_UAT.md) DEV-REP-01 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。写路径仅确认→取消（新建/批量指派）。  
> **源码**：`clients/admin-vue/src/views/devices/RepairTicketsView.vue`  
> **API**：`GET/POST /api/v2/ops/admin/repair-tickets`；详情 `/{id}`；`batch-assign`；`/{id}/transition`  
> **视口**：主测 **1366×768**；窄/宽用 MCP `browser_resize`。  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/repair-tickets/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/repair-tickets/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/repair-tickets/FINDINGS.md) |
| 统计 | PASS · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 标题「维修工单」· 批量指派（无勾选 disabled）· 新建工单 |
| 筛选 | 状态 · 设备 · 优先级 · 故障类型 · 查询 |
| 表 | CrudTable：勾选 / 升降序 / 刷新 / 列设置；行「详情」；OPEN/IN_PROGRESS 另有开始/完成/取消 |
| 弹层 | 新建维修工单 · 批量指派 |
| 抽屉 | 工单详情 + 流转记录 |

---

## 2. 用例（REP-*）

| ID | 步骤 | 期望 |
|----|------|------|
| REP-01 | 打开 `/repair-tickets` | 共 N↔API；状态中文 |
| REP-02 | 未勾选 | 「批量指派」disabled |
| REP-03 | 状态=已完成 | total↔API DONE |
| REP-04 | 状态=待处理 | total=0 +「暂无维修工单」 |
| REP-05 | 设备=发号柜 | total↔API deviceId |
| REP-06 | `?deviceId=` 深链 | 筛选回显 + 列表对齐 |
| REP-07 | 故障=门锁 / 优先级=普通 | total↔API |
| REP-08 | 详情 | 抽屉 + 流转记录中文 |
| REP-09 | 设备名链接 | `/devices/{id}` |
| REP-10 | 新建 → 取消 | 不落库 |
| REP-11 | 勾选 → 批量指派 → 取消 | 不落库 |
| REP-U-01 | 900 视口 | 无整页横滚 |

---

## 3. 结案清单

- [x] 共 1（已完成 · 发号柜 · 门锁 · 普通）
- [x] 待处理空态诚实；深链 deviceId 回显
- [x] 详情 3 条流转；新建/指派取消
- [x] 本环境无 OPEN 行 → 流转写路径跳过（非 FAIL）
- [x] 窄 900 / 宽 1920；结束 1366
- [x] BUTTONS / FINDINGS / Changelog

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 列表 | 共 **1** · #1 完整轮报修-门磁异常 · 已完成 |
| 筛选 | DONE=1 · OPEN=0 · DOOR=1 · NORMAL=1 · 发号柜=1 |
| 详情 | 流转 3 条（创建→处理中→已完成） |
| 写 | 新建取消 · 批量指派取消 |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
