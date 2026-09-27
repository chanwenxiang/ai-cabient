# 补货员效率 · 按钮清点 · 2026-09-26

> `/replenishment-staff` · Playwright **1366×768**（DPR=1）  
> [`REPLENISHMENT_STAFF_FULL_BROWSER_UAT.md`](../../../uat/REPLENISHMENT_STAFF_FULL_BROWSER_UAT.md)  
> 本页**无硬写**；筛选/导出/刷新只读。

---

## A 头 / 天数 / 列表

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开页 · 默认 30 天 | ✓ | 共 1 · 完成率 60.0% · `rs-01-home.png` |
| A-02 | 表头中文 | ✓ | 「已完成」无 COMPLETED |
| A-03 | 近 7 天 | ✓ | 共 0 · 空态 · `rs-02-days7.png` |
| A-04 | 近 90 天 | ✓ | 共 1 · `rs-03-days90.png` |
| A-05 | 回 30 天 | ✓ | 共 1 |

## B 筛选 / 工具

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 关键词「默认」片段 | ✓ | 共 1 · `rs-04-keyword.png` |
| B-02 | 无匹配关键词 | ✓ | 共 **0** · 暂无补货任务数据 · `rs-05-keyword-empty.png` |
| B-03 | 重置 | ✓ | 关键词清空 · 共 1 · `rs-06-reset.png` |
| B-04 | 导出 CSV | ✓ | `补货员效率_*.csv` · `rs-07-export.png` |
| B-05 | 刷新 / 列设置 | ✓ | `rs-08-refresh.png` · `rs-09-cols.png` |
| B-06 | 行勾选 | ✓ | 无批量写 · `rs-10-select.png` |

## C UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| C-01 | 900 无整页横滚 | ✓ | `rs-ux-narrow.png` |
| C-02 | 1366 / 1920 | ✓ | `rs-ux-*.png` |
| C-03 | 结束视口 1366 | ✓ | `rs-99-end.png` |

## 结案

- [x] FAIL 0；无硬写；视口结束 1366
|
