# 日志中心 · 按钮清点 · 2026-09-27

> `/observability` · Playwright **1366×768**（DPR=1）  
> [`OBSERVABILITY_FULL_BROWSER_UAT.md`](../../../uat/OBSERVABILITY_FULL_BROWSER_UAT.md)  
> 规则：只读；无观测栈诚实空；不硬起 Grafana。

---

## A 头 / 空态

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开 · 标题 | ✓ | 日志中心 · `ob-01-home.png` |
| A-02 | Alert | ✓ | Grafana 未启动，无法嵌入看板 |
| A-03 | Empty | ✓ | 同文 · iframe=0 |
| A-04 | 刷新状态 | ✓ | `ob-02-refresh.png` |
| A-05 | 新窗口打开 | ✓ 隐藏 | 离线 `openBtn=0` |

## B 页签 ×6

| # | 页签 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 全栈日志流 | ✓ 空态 | `ob-03-tab-stream.png` |
| B-02 | 错误与告警 | ✓ | `ob-04-tab-errors.png` |
| B-03 | 日志速率 | ✓ | `ob-05-tab-rate.png` |
| B-04 | ERROR 计数 | ✓ | `ob-06-tab-errorcount.png` |
| B-05 | 一次调用追踪 | ✓ | `ob-07-tab-trace.png` |
| B-06 | 运营概览 | ✓ | `ob-08-tab-overview.png` |

## C 其它 / UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| C-01 | 其它入口 tip×3 | ✓ | `ob-10-tips.png` |
| C-02 | 900 / 1366 / 1920 | ✓ | `ob-ux-*` · `ob-99-end.png` |

## 结案

- [x] FAIL 0；FINDING 0（F1 已修）；未硬起观测栈
- [x] hint 复核 `ob-fix-hint6.png`
|
