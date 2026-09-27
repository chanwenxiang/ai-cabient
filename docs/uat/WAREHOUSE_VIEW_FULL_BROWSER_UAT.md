# 仓库 · 全量浏览器 UAT（单页深测册）

> **地位**：侧栏「履约仓储 → 仓库」**单页执行真源**。比 [`WAREHOUSE_FULL_BROWSER_UAT.md`](./WAREHOUSE_FULL_BROWSER_UAT.md) 仓库项更细。  
> **工具铁律**：Playwright 真实打开/点击；禁止仅 curl。写路径仅确认→取消（新增/新建/清理）。  
> **源码**：`clients/admin-vue/src/views/warehouse/WarehouseView.vue` + `components/warehouse/*`  
> **API**：`/api/v2/ops/admin/warehouse/*`（list / inventory / outbounds / in-transit / movements / …）  
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
| 截图 | `docs/uat-screenshots/2026-09-26/warehouse-view/` |
| 明细 | [`BUTTONS.md`](../uat-screenshots/2026-09-26/warehouse-view/BUTTONS.md) · [`FINDINGS.md`](../uat-screenshots/2026-09-26/warehouse-view/FINDINGS.md) |
| 统计 | **PASS** · FAIL 0 · BLOCK 0 |

---

## 1. 结构

| 区 | 内容 |
|----|------|
| 分组 | 基础(1) · 采购(5) · 库存(4) · 履约(3) |
| 基础 | 仓库概览 |
| 采购 | 供应商 / 采购单 / 采购建议 / 采购退货 / 应付账款 |
| 库存 | 盘点单 / 货位 / 批次库存 / 库存流水 |
| 履约 | 仓间调拨 / 出库单 / 在途 |

---

## 2. 用例（WHV-*）

| ID | 步骤 | 期望 |
|----|------|------|
| WHV-01 | 打开 `/warehouse` | 标题仓库；共 1；四分组 |
| WHV-02 | 切四分组 | Tab 数与 hint 一致 |
| WHV-03+ | 各「新增/新建」→ 取消 | 弹层关闭不落库 |
| WHV-09 | 按建议生成采购单（空） | toast「当前没有可用的采购建议」 |
| WHV-16 | 批次库存 | 共 8↔API |
| WHV-20/21 | 出库单 · 清理空草稿→取消 | 确认框 dismiss |
| WHV-22/23 | 在途 · `?overdue=1` | hint + 勾选回显 |
| WHV-24 | `?tab=purchase` | 深链落地 |
| WHV-U-01 | 900 | 无整页横滚 |

---

## 3. 结案清单

- [x] 四分组 1/5/4/3；仓库 WH-DEMO-001；库存 8；流水 86；出库 5
- [x] 十余处新建弹层均取消；清理空草稿取消
- [x] 采购建议空态 toast；在途/采购深链
- [x] BUTTONS / FINDINGS / Changelog；WAREHOUSE 册深链

---

## 4. 执行摘要（2026-09-26）

| 维度 | 结果 |
|------|------|
| 基础 | 共 **1** · 新增仓库取消 |
| 采购 | 供应商1 / 采购单1；建议空 toast；退货/应付可开 |
| 库存 | 批次 **8** · 流水 **86** · 盘点/货位弹层取消 |
| 履约 | 出库 **5** · 清理取消 · 在途 0 + overdue 深链 |
| 视口 | 窄 900 无横滚 · 结束 1366 |
|
