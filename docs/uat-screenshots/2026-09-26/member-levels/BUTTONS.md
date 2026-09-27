# 会员等级规则 · 按钮清点 · 2026-09-27

> `/member-levels` · Playwright **1366×768**（DPR=1）  
> [`MEMBER_LEVELS_FULL_BROWSER_UAT.md`](../../../uat/MEMBER_LEVELS_FULL_BROWSER_UAT.md)  
> 规则：新建/编辑/批量启停 **打开→取消**；行启停禁止盲点（无确认）。

---

## A 头 / 列表

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开 · 共 4 | ✓ | 普通/白银/黄金/铂金 · `ml-01-home.png` |
| A-02 | 新建等级 | ✓ 可见 | |
| A-03 | 状态中文 · ¥ 区间 | ✓ | 启用；消费区间含 ¥ |

## B 写路径

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 新建等级 → 取消 | ✓ | `ml-02-create-open.png` · `ml-03-create-cancel.png` |
| B-02 | 编辑 → 取消 | ✓ | 编码 NORMAL disabled · `ml-04-edit-open.png` |
| B-03 | 行启停 | ✓ 可见未点* | 停用钮可见 · `ml-05-toggle-visible.png` → FINDING-1 |
| B-04 | 批量停用 → 取消 | ✓ | MessageBox「确认批量停用选中的 1 条」→ 取消 · `ml-06-batch-confirm.png` |

\* 源码 `toggleStatus()` 无 `ElMessageBox`，禁止盲点。

## C 工具 / UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| C-01 | 刷新 · 列设置 | ✓ | `ml-07-tools.png` |
| C-02 | 导出 | — 无 | 本页无 CSV 导出 |
| C-03 | 900 / 1366 / 1920 | ✓ | `ml-ux-*` · `ml-99-end.png` |

## 结案

- [x] FAIL 0；FINDING 1；新建/编辑/批停均取消；行启停未点
|
