# 投放地图 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「设备商品 → 投放地图」**单页执行真源**。比 [`DEVICE_SKU_FULL_BROWSER_UAT.md`](./DEVICE_SKU_FULL_BROWSER_UAT.md) DEV-MAP-01 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。本页无资金写路径。  
> **源码**：`clients/admin-vue/src/views/devices/DeviceMapView.vue`  
> **API**：`GET /api/v2/ops/admin/devices/map-points?lifecycleStatus=`  
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
| 截图 | `docs/uat-screenshots/2026-09-26/device-map/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/device-map/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/device-map/FINDINGS.md) |
| 统计 | PASS · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 地图 | Leaflet + marker cluster；底图高德→备用 |
| 浮层筛选 | 在线 · 自营 · 机器编号 · 地区 · 关键字 · 生命周期 · 查询/刷新 |
| 计数 | 「共 N 个柜机落点」 |
| 侧栏 | 点位列表 · 在线标签 · 详情深链 |

---

## 2. 用例（MAP-*）

| ID | 步骤 | 期望 |
|----|------|------|
| MAP-01 | 打开 `/device-map` | Leaflet 就绪；默认「全部有坐标」；落点 N↔API(ALL) |
| MAP-02 | 勾选「在线」 | 仅在线；count 对齐 |
| MAP-03 | 勾选「自营」 | 有数或诚实空「暂无落点」 |
| MAP-04 | 机器编号 | 命中 1 台 |
| MAP-05 | 关键字无匹配 | 共 0 + 空态 |
| MAP-06 | 生命周期=投放 | 共 1（发号柜）↔ API DEPLOYED |
| MAP-07 | 点侧栏项 | 地图聚焦 |
| MAP-08 | 「详情」 | `/devices/{id}` |
| MAP-09 | 查询 / 刷新 | 可点重载 |
| MAP-10 | 地区=上海 | 落点收窄 |
| MAP-U-01 | 900 视口 | 无整页横滚；地图仍在 |

---

## 3. 结案清单

- [x] 默认 ALL=3；投放=1；在线=1；地区上海=2
- [x] 自营/关键字空态中文诚实
- [x] 详情深链（投放筛后 → 发号柜）
- [x] 窄 900 / 宽 1920；结束 1366
- [x] BUTTONS / FINDINGS / Changelog

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 默认 | 共 **3** 个柜机落点（含入库演示柜，已知） |
| 投放 | 共 **1** · 浏览器自动发号柜 |
| 在线 | 共 1 |
| 自营 | 共 0（coopMode 空）· 空态诚实 |
| 详情 | `/devices/777740024057` |
| 视口 | 窄 900 无整页横滚 · 结束 1366 |
|
