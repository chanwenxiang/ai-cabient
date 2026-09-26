# 运营后台 ·「设备商品」全量浏览器 UAT（Phase 3）

> **地位**：第三册（侧栏「设备商品」）。工具/四维铁律同 [`OVERVIEW_FULL_BROWSER_UAT.md`](./OVERVIEW_FULL_BROWSER_UAT.md) §1。  
> **版本**：1.1 · 2026-09-26（侧栏十页复测，含识别映射）  
> **截图**：`docs/uat-screenshots/2026-09-26/device-sku/`

---

## 0. 范围顺序

| 序 | 路径 | 标题 |
|----|------|------|
| 1 | `/device-ops` | 设备运维 |
| 2 | `/devices` | 设备管理 |
| 3 | `/device-map` | 投放地图 |
| 4 | `/device-kpi` | 设备可用性 |
| 5 | `/repair-tickets` | 维修工单 |
| 6 | `/skus` | 商品管理 |
| 7 | `/sku-review` | 选品诊断 |
| 8 | `/sku-vision` | 识别入驻 |
| 9 | `/vision-mappings` | 识别映射 |
| 10 | `/upload-queue` | 录像上传 |

| 字段 | 值 |
|------|-----|
| 结案 | **Phase 3 Done（复测）**；[`device-sku/BUTTONS.md`](../uat-screenshots/2026-09-26/device-sku/BUTTONS.md) + [`FINDINGS.md`](../uat-screenshots/2026-09-26/device-sku/FINDINGS.md) |

ID 前缀：`DEV-*` · `SKU-*`

---

## 1. 关键检查

| ID | 页 | 期望 |
|----|-----|------|
| DEV-LIST-01 | 设备列表 | 行数/lifecycle 筛选；禁默认演示污染（CAB-001 勿当投放主路径） |
| DEV-LIST-02 | 深链 | `lifecycleStatus=DEPLOYED&online=OFFLINE` 筛选回显 |
| DEV-MAP-01 | 地图 | 点位加载或诚实空；图例与 KPI 不混读 |
| DEV-KPI-01 | 可用性 | KPI 有数或空态中文 |
| DEV-OPS-01 | 运维 | 列表可开；锁机操作须确认 |
| DEV-REP-01 | 维修 | 列表/新建入口可见 |
| SKU-01 | 商品 | 列表金额/条码可读；编辑点到弹层可取消 |
| SKU-MAP-01 | 识别映射 | 类名→商品列表；新增可取消 |

---

## 2. 执行日志

见 `docs/uat-screenshots/2026-09-26/device-sku/FINDINGS.md` + `BUTTONS.md`。

## 3. Done 定义

- [x] 关键 ID 均有 PASS/SKIP + 证据  
- [x] 锁机 / 批量操作 / SKU 编辑下架写路径确认后取消  
- [x] 附录 B 已落盘 `device-sku/BUTTONS.md`  
- [x] **2026-09-26 复测**：侧栏十页按概览同款附录 B；含 `/vision-mappings`；截图 `ds-*.png`  
