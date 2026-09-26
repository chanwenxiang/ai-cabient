# 设备管理 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「设备商品 → 设备管理」**单页执行真源**。比 [`DEVICE_SKU_FULL_BROWSER_UAT.md`](./DEVICE_SKU_FULL_BROWSER_UAT.md) DEV-LIST-* 更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。批量锁机/解锁/投放/退役与新建一律 **确认框/弹层后取消**。  
> **源码**：`clients/admin-vue/src/views/devices/DeviceListView.vue`  
> **API**：`GET /api/v2/ops/admin/devices`  
> **视口**：主测 **1366×768**；窄/宽用 MCP `browser_resize`，禁止脚本内 goto 冲掉视口。  
> **版本**：1.0 · 2026-09-26

---

## 0. 元信息

| 字段 | 值 |
|------|-----|
| 执行人 | Cursor Agent + Playwright MCP |
| 日期 | 2026-09-26 |
| 视口 | 1366×768；抽检 900 / 1920 |
| 账号 | 运营超管 `13900000001` |
| 截图 | `docs/uat-screenshots/2026-09-26/devices/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/devices/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/devices/FINDINGS.md) |
| 统计 | PASS · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 头 | 批量锁机/解锁/投放/未投放/退役 · 新建设备 |
| 看板 | 全部设备 · 在线 · 离线 · 可购买 · 未锁机 · 已锁机 |
| Tab | 同上六态（与看板联动） |
| 筛选 | 关键词 · 生命周期 · 合作方式 · 路线 · 查询/重置 |
| 行 | 详情 · 退款设置 |
| 表工具 | 导出 · 刷新 · 列设置 · 升降序 |

---

## 2. 用例（DM-*）

| ID | 步骤 | 期望 |
|----|------|------|
| DM-01 | 打开 `/devices` | 共 N = API；看板计数对齐 |
| DM-02 | Tab 在线 | `?online=ONLINE`；total↔API |
| DM-03 | Tab 离线 | `?online=OFFLINE` |
| DM-04 | Tab 可购买 | `online=ONLINE&salesLocked=false` |
| DM-05 | Tab 已锁机 | `salesLocked=true` |
| DM-06 | Tab 全部 | 回到全量 |
| DM-07 | 看板「离线」 | URL/列表同离线 Tab |
| DM-08 | `?lifecycleStatus=DEPLOYED&online=OFFLINE` | total↔API；筛选回显 |
| DM-09 | 关键词=设备编号 | ≥1 行 |
| DM-10～13 | 批量解锁/锁机/投放/退役 | 确认 → **取消** |
| DM-14 | 新建设备 | 弹层 → 取消 |
| DM-15 | 行详情 | `/devices/{id}` |
| DM-16 | 退款设置 | 弹层 → 取消 |
| DM-17 | 导出 | CSV |
| DM-U-01 | 900 视口 | 无整页横滚 |

---

## 3. 结案清单

- [x] 全量/在线/离线/可购买/已锁机 ↔ API
- [x] 深链 DEPLOYED+OFFLINE = 1（777740024057）
- [x] 批量四类 + 新建 + 退款设置均取消
- [x] 详情深链；导出 CSV；中文状态无裸码
- [x] CAB-001 为**入库**演示柜，非投放主路径（已知）
- [x] BUTTONS / FINDINGS / Changelog

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 全量 | 共 **3**；看板 在线1 / 离线2 / 已锁机3 / 可购买0 |
| 深链 | DEPLOYED+OFFLINE → 共 1 · 发号柜 |
| 写路径 | 批量解锁/锁机/投放/退役 + 新建 + 退款设置 → 均取消 |
| 导出 | `设备_20260926_*.csv` |
| 视口结束 | vw=1366 · 窄 900 无整页横滚 |
|
