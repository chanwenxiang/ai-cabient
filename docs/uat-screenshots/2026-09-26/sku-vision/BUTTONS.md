# 识别入驻 · 按钮清点 · 2026-09-26

> `/sku-vision` · Playwright **1366×768**  
> [`SKU_VISION_FULL_BROWSER_UAT.md`](../../../uat/SKU_VISION_FULL_BROWSER_UAT.md)

---

## A 头 / Chip / Tab

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 首页 | ✓ | 共 6 · 中文标签 · `vis-01-home.png` |
| A-02 | 入驻说明 / 收起 | ✓ | `vis-02-help.png` |
| A-03 | Chip「1 草稿」 | ✓ | URL `enrollment=DRAFT` · 共 6 · `vis-03-chip-draft.png` |
| A-04 | Tab「所有商品」 | ✓ | 共 6 · `vis-04-all.png` |
| A-05 | 入驻配置 → 取消 | ✓ | `vis-09-enroll.png` |
| A-06 | 商品管理 | ✓ | → `/skus` · `vis-13-skus.png` |

## B 筛选

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 关键词「可乐」 | ✓ | 共 **1** · `vis-05-keyword.png` |
| B-02 | 无匹配 | ✓ | 共 **0** · `vis-06-keyword-empty.png` |
| B-03 | 重置 | ✓ | 共 6 · `vis-07-reset.png` |
| B-04 | 识别状态「草稿」 | ✓ | 共 6 · `vis-08-status.png` |

## C 行 / 写（取消）

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 行名 → 编辑弹层 → 取消 | ✓ | `vis-10-edit.png` |
| C-02 | 行图标「识别测试」→ 关闭 | ✓ | 图标按钮（非文案）· `vis-11-test.png` |
| C-03 | 批量下架 → 取消 | ✓ | `vis-12-batch-delist.png` |
| C-04 | 更多→「推进到映射中」 | ✓ 可见 | UAT **未点**（会写库） |
| C-05 | 导出 / 导入 / 刷新 | ✓ | 工具栏可见 |

## D UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | 900 无整页横滚 | ✓ | `vis-ux-narrow.png` |
| D-02 | 1366 / 1920 | ✓ | `vis-ux-*.png` |
| D-03 | 结束视口 1366 | ✓ | `vis-99-end.png` |

## 结案

- [x] FAIL 0；软写均取消；视口结束 1366
|
