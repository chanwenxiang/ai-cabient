# 定时任务 · 按钮清点 · 2026-09-27

> `/scheduled-tasks` · Playwright **1366×768**（DPR=1）  
> [`SCHEDULED_TASKS_FULL_BROWSER_UAT.md`](../../../uat/SCHEDULED_TASKS_FULL_BROWSER_UAT.md)  
> 规则：新增/编辑/执行/批操→取消；**禁止**拨行内启停开关。

---

## A 头 / 筛

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开 · 共 32 | ✓ | `st-01-home.png` |
| A-02 | 关键词无命中 | ✓ | 暂无定时任务 · `st-02` |
| A-03 | 重置 | ✓ | 32 · `st-03` |
| A-04 | 新增 | ✓ | |
| A-05 | 行内启停开关 | ✓ 可见 · **未拨** | |

## B 写路径（软取消）

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 新增定时任务 → 取消 | ✓ | `st-04-create-open.png` |
| B-02 | 编辑 → 取消 | ✓ | 标识 disabled · `st-05-edit-open.png` |
| B-03 | 立即执行 → 取消 | ✓ | 「确认立即执行「补偿任务处理」？」· `st-06-run-confirm.png` |
| B-04 | 删除 | SKIP | 无自定义可删 · `st-07-delete-probe.png` |
| B-05 | 批量停用 → 取消 | ✓ | `st-08-batch-disable.png` |
| B-06 | 批量执行 → 取消 | ✓ | `st-09-batch-run.png` |

## C 工具 / UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| C-01 | 导出 CSV | ✓ | `定时任务_20260927_111900.csv` · `st-10` |
| C-02 | 刷新 | ✓ | `st-11-refresh.png` |
| C-03 | 900 / 1366 / 1920 | ✓ | `st-ux-*` · `st-99-end.png` |

## 结案

- [x] FAIL 0；FINDING 0；开关未硬写
|
