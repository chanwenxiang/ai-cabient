# 活动效果分析 · 按钮清点 · 2026-09-27

> `/marketing-roi` · Playwright **1366×768**（DPR=1）  
> [`MARKETING_ROI_FULL_BROWSER_UAT.md`](../../../uat/MARKETING_ROI_FULL_BROWSER_UAT.md)  
> 规则：本页只读；无硬写。

---

## A 头 / 列表

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开 · 空态 | ✓ | 「暂无活动数据」· 共 0 · 默认近 30 天 · `roi-01-home.png` |
| A-02 | hint | ✓ | 发券→核销→带动营收 |

## B 筛选 / 账期

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 关键词无匹配 | ✓ | 共 0 · `roi-02-kw-empty.png` |
| B-02 | 重置 | ✓ | 关键词清空 · `roi-03-reset.png` |
| B-03 | 近 7 天 | ✓ | active · API `days=7` · `roi-04-days7.png` |
| B-04 | 近 90 天 | ✓ | active · API `days=90` · `roi-05-days90.png` |

## C 工具 / UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| C-01 | 导出（空表） | ✓ | toast「暂无数据可导出」· `roi-06-export.png` |
| C-02 | 刷新 | ✓ | `roi-07-refresh.png` |
| C-03 | 900 / 1366 / 1920 | ✓ | `roi-ux-*` · `roi-99-end.png` |

## 结案

- [x] FAIL 0；FINDING 0；只读；空态诚实
|
