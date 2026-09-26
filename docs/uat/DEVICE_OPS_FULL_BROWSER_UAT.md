# 设备运维 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「设备商品 → 设备运维」**单页执行真源**。比 [`DEVICE_SKU_FULL_BROWSER_UAT.md`](./DEVICE_SKU_FULL_BROWSER_UAT.md) DEV-OPS-01 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。本页为**事件只读列表**（无锁机写路径；锁机在设备详情）。  
> **源码**：`clients/admin-vue/src/views/devices/DeviceOpsMonitorView.vue`  
> **API**：`GET /api/v2/ops/admin/device-ops/events`；设备筛 `GET /api/v2/ops/admin/devices/ref`  
> **视口**：主测 **1366×768**；窄/宽用 MCP `browser_resize`，禁止脚本内 `setViewportSize`+`goto` 冲掉视口。  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/device-ops/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/device-ops/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/device-ops/FINDINGS.md) |
| 统计 | PASS · FAIL 0 · BLOCK 0（含 #208） |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 筛选 | 类型 · 级别 · 设备 · 关键词（前端本页过滤） |
| 表工具 | 升/降序 · 导出 · 刷新 · 列设置 |
| 列 | 事件ID / 类型 / 级别 / 设备名 / 设备编号 / 标题 / 详情 / 账龄 / 时间 |

---

## 2. 用例（DO-*）

| ID | 步骤 | 期望 |
|----|------|------|
| DO-01 | 打开 `/device-ops` | 标题「设备运维」；total↔API |
| DO-02 | 类型=离线 | 行类型均为「离线」；total 变化 |
| DO-03 | 级别=警告 | 行级别均为「警告」 |
| DO-04 | 设备=发号柜 | 行设备编号一致 |
| DO-05 | 关键词无匹配 | 空态「暂无运维事件」且 **共 0 条**（#208） |
| DO-05b | 关键词=事件 ID 片段 | total=可见行数 |
| DO-06 | 降序 | 首行 eventId 变大 |
| DO-07 | 导出 | CSV `设备运维事件_*.csv` |
| DO-08 | 刷新 / 列设置 | 可点；列设置后 Escape |
| DO-U-01 | 900 视口 | 无整页横滚 |

---

## 3. 结案清单

- [x] total=69 ↔ API；类型/级别/设备中文无裸码
- [x] 关键词空表 total=0（#208）
- [x] 导出 / 升降序 / 刷新 / 列设置
- [x] 窄 900 / 宽 1920；结束 1366
- [x] BUTTONS / FINDINGS / Changelog / lessons #208

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 全量 | 共 **69** 条 = API |
| 类型=离线 | 共 29 |
| 级别=警告 | 共 39 |
| 设备筛选 | 共 40（777740024057） |
| 关键词空 | 共 **0** 条（修前仍显示 69） |
| 导出 | `设备运维事件_20260926_*.csv` |
|
