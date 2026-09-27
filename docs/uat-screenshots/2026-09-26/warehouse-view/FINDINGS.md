# 仓库 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`WAREHOUSE_VIEW_FULL_BROWSER_UAT.md`](../../../uat/WAREHOUSE_VIEW_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

四分组与子 Tab 齐全；库存/出库与 API 对齐；十余处新建弹层均取消；清理空草稿确认 dismiss；在途/采购深链正确。结束视口 **1366×768**。

## 数据对照

| 项 | UI | API |
|----|-----|-----|
| 仓库概览 | 共 1 · WH-DEMO-001 正常 | list total=1 |
| 批次库存 | 共 **8** | inventory total=8 |
| 库存流水 | 共 **86** | movements total=86 |
| 出库单 | 共 **5** | outbounds total=5 |
| 在途 | 共 0 | in-transit total=0 |
| 采购建议 | 共 0 · 空态文案 | — |

## 本轮缺陷

无 FAIL。无需改码。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 「按建议生成采购单」在建议为空时**不弹窗**，toast「当前没有可用的采购建议」（诚实空态）。 |
| 2 | 分组角标「基础 1 / 采购 5 / …」为**子 Tab 个数**，非行数。 |
| 3 | 采购单默认可勾「隐藏 E2E/冒烟测试单」。 |
| 4 | 出库默认状态筛「待处理」；清理确认主按钮为「确认清理」，dismiss 用 `取消` exact。 |
| 5 | 在途说明：到柜由补货完成签收；本页不办回仓入库。 |

## 证据

`whv-01`…`whv-25` · `whv-ux-*` · `whv-99-end`
|
