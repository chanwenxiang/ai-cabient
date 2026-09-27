# 仓库 · 按钮清点 · 2026-09-26

> `/warehouse` · Playwright **1366×768**  
> [`WAREHOUSE_VIEW_FULL_BROWSER_UAT.md`](../../../uat/WAREHOUSE_VIEW_FULL_BROWSER_UAT.md)

---

## A 分组 / 概览

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 首页 | ✓ | 共 1 · WH-DEMO-001 · `whv-01-home.png` |
| A-02 | 基础/采购/库存/履约 | ✓ | 1/5/4/3 · `whv-02-group-*.png` |
| A-03 | 新增仓库 → 取消 | ✓ | `whv-03-warehouse.png` |
| A-04 | 导出 / 导入模板 | ✓ | 可见 |

## B 采购

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 供应商 | ✓ | 共 1 · `whv-04-suppliers.png` |
| B-02 | 新增供应商 → 取消 | ✓ | `whv-05-supplier.png` |
| B-03 | 采购单 + 隐藏测试单 | ✓ | `whv-06-purchase.png` |
| B-04 | 新建采购单 → 取消 | ✓ | `whv-07-purchase-create.png` |
| B-05 | 采购建议 | ✓ | 空态中文 · `whv-08-suggestions.png` |
| B-06 | 按建议生成采购单 | ✓ | toast「当前没有可用的采购建议」· `whv-09-suggest-po.png` |
| B-07 | 新建退货 → 取消 | ✓ | `whv-10-return.png` |
| B-08 | 应付账款 + 仅看逾期 | ✓ | `whv-11-payables.png` |

## C 库存

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 新建盘点 → 取消 | ✓ | `whv-12-stocktake.png` |
| C-02 | 新增货位 / 入库 / 移库 → 取消 | ✓ | `whv-13`…`15` |
| C-03 | 批次库存 | ✓ | 共 **8** · `whv-16-inventory.png` |
| C-04 | 其他入库 → 取消 | ✓ | `whv-17-inbound.png` |
| C-05 | 库存流水 | ✓ | 共 **86** · `whv-18-movements.png` |

## D 履约 / 深链

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| D-01 | 新建调拨 → 取消 | ✓ | `whv-19-transfer.png` |
| D-02 | 出库单 | ✓ | 共 5 · 状态筛 · `whv-20-outbounds.png` |
| D-03 | 清理空草稿 → 取消 | ✓ | `whv-21-cleanup.png` |
| D-04 | 在途 hint | ✓ | `whv-22-transit.png` |
| D-05 | `?tab=transit&overdue=1` | ✓ | 勾选回显 · `whv-23-deeplink-overdue.png` |
| D-06 | `?tab=purchase` | ✓ | `whv-24-deeplink-purchase.png` |

## E UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| E-01 | 900 无整页横滚 | ✓ | `whv-ux-narrow.png` |
| E-02 | 结束视口 1366 | ✓ | `whv-99-end.png` |

## 结案

- [x] FAIL 0；软写均取消；视口结束 1366
|
