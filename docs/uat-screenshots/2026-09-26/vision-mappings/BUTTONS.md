# 识别映射 · 按钮清点 · 2026-09-26

> `/vision-mappings` · Playwright **1366×768**  
> [`VISION_MAPPINGS_FULL_BROWSER_UAT.md`](../../../uat/VISION_MAPPINGS_FULL_BROWSER_UAT.md)

---

## A 头 / 列表

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 首页 | ✓ | 共 4 · 中文标签 · `map-01-home.png` |
| A-02 | 导出 / 刷新 | ✓ | 工具栏可见 |
| A-03 | 识别入驻 | ✓ | → `/sku-vision` · `map-12-sku-vision.png` |
| A-04 | 商品管理 | ✓ | → `/skus` · `map-13-skus.png` |

## B 筛选

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 关键词「可乐」 | ✓ | 共 **2** · `map-02-keyword.png` |
| B-02 | 无匹配 | ✓ | 共 **0** · `map-03-keyword-empty.png` |
| B-03 | 重置 | ✓ | 共 4 · `map-04-reset.png` |
| B-04 | 深链 `?keyword=可乐` | ✓ | 输入回显 · 共 2 · `map-05-deeplink.png` |

## C 写（取消）

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 新增映射 → 取消 | ✓ | `map-06-create.png` |
| C-02 | 行编辑 → 取消 | ✓ | `map-07-edit.png` |
| C-03 | 行删除 → 取消 | ✓ | `map-08-delete.png` |
| C-04 | 阿里云新增 → 取消 | ✓ | `map-09-aliyun-create.png` |
| C-05 | 阿里云编辑/删除 | — SKIP | 本机无行 · `map-10-aliyun-empty.png` |

## D UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | 900 无整页横滚 | ✓ | `map-ux-narrow.png` |
| D-02 | 1366 / 1920 | ✓ | `map-ux-*.png` |
| D-03 | 结束视口 1366 | ✓ | `map-99-end.png` |

## 结案

- [x] FAIL 0；软写均取消；视口结束 1366
|
