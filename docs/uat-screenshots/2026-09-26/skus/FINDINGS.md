# 商品管理 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`SKUS_FULL_BROWSER_UAT.md`](../../../uat/SKUS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

在售列表共 6；金额 `¥x.xx`、状态「在售」中文；关键词/深链/空态正确；类目无数据诚实空；新建/编辑/批量下架均取消未落库；识别入驻可达。结束视口 **1366×768**。

## 数据对照

| 筛 | UI | 说明 |
|----|-----|------|
| 在售默认 | 共 6 | UI 请求 `status=ACTIVE`（`q` 未传） |
| 所有商品 | 共 6 | 本环境无 INACTIVE |
| 关键词「可乐」 | 共 1 | API 参数名是 **`q`**（非 keyword） |
| 无匹配 | 共 0 | 空态「在售列表无匹配…」 |
| `?keyword=可乐` | 输入回显 · 共 1 | 路由 query 用 keyword，请求转 `q` |
| 类目「生鲜」 | 共 0 | 诚实空 |

样例行：100063 可口可乐 330ml · 条码 6901028300018 · ¥3.50 / 成本 ¥1.90 · 饮料 · 在售

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **列表查询关键词参数是 `q`**；URL 深链仍用 `?keyword=`，`skuQueryParams` 映射为 `q`。裸调 API 用 `keyword=` 会当成无筛。 |
| 2 | **类目字典值**与行内 `category` 可能是中文标签（如「饮料」）直接存库，筛选走字典选项。 |
| 3 | 工具栏「打印标签」未勾选 disabled；行内另有 `aria-label=打印标签` 图标（勿与工具栏 `getByRole` 混淆 → strict mode）。 |

## UAT 操作注意

| # | 说明 |
|---|------|
| 1 | 点「打印标签」须限定 `.filter-bar` / `form`，否则与行图标撞 strict mode。 |
| 2 | EP Select 选项用 `[role=option]` + `aria-controls`（lesson #209）。 |

## 证据

`sku-01`…`sku-11` · `sku-ux-*` · `sku-99-end`
|
