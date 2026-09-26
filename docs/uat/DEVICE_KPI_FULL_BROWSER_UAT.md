# 设备可用性 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「设备商品 → 设备可用性」**单页执行真源**。比 [`DEVICE_SKU_FULL_BROWSER_UAT.md`](./DEVICE_SKU_FULL_BROWSER_UAT.md) DEV-KPI-01 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。本页无写路径。  
> **源码**：`clients/admin-vue/src/views/devices/DeviceKpiView.vue`  
> **API**：`GET /api/v2/ops/admin/device-availability-kpi?date=`（无 date=当天实时）  
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
| 截图 | `docs/uat-screenshots/2026-09-26/device-kpi/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/device-kpi/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/device-kpi/FINDINGS.md) |
| 统计 | PASS · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 标题「设备可用性」· 提示「默认当天实时口径…」 |
| 操作 | 日期选择（禁未来）· 刷新 |
| KPI 网格 | 9 卡：统计日期 / 设备总数 / 离线事件 / 自动锁机 / 自动解锁 / 人工解锁 / 平均锁定时长 / 平均恢复时长 / 人工介入率 |

---

## 2. 用例（KPI-*）

| ID | 步骤 | 期望 |
|----|------|------|
| KPI-01 | 打开 `/device-kpi` | 9 卡有数；总数↔API；空时长「暂无样本」 |
| KPI-02 | 空态中文 | 无 null/undefined/NaN；介入率有样本为 % |
| KPI-03 | 刷新 | 可点；数值稳定 |
| KPI-04 | 改日期=昨天 | 统计日期/介入率随 API 快照变 |
| KPI-05 | 切回今天 | 恢复当日口径 |
| KPI-06 | 日期面板 | 未来日 disabled |
| KPI-U-01 | 900 视口 | 无整页横滚；9 卡仍在 |

---

## 3. 结案清单

- [x] 今日：总数 3 · 人工解锁 1 · 介入率 100.0% · 时长「暂无样本」
- [x] 昨日：人工解锁 0 · 介入率「无解锁」
- [x] 未来日禁用；窄 900 / 宽 1920；结束 1366
- [x] BUTTONS / FINDINGS / Changelog

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 今日 API | deviceTotal=3 · manualUnlock=1 · rate=1 → UI 100.0% |
| 昨日 API | manualUnlock=0 · rate=0 → UI「无解锁」 |
| 空态 | avgLock/avgRecover →「暂无样本」 |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
