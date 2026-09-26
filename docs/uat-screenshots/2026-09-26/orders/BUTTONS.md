# 订单管理 · 按钮清点 · 2026-09-26

> `/orders` · Playwright **1366×768**  
> [`ORDERS_FULL_BROWSER_UAT.md`](../../../uat/ORDERS_FULL_BROWSER_UAT.md)  
> 资金写路径：确认框 → **取消**（#200）

---

## A 页头 / Tab / 筛选

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开页 | ✓ | `ord-01-home.png` · API total=24 |
| A-02 | Tab 全部 | ✓ | 默认隐藏零元 · 本页 10 行 |
| A-03 | Tab 待支付 | ✓ | 共 0 ·「暂无订单」 |
| A-04 | Tab 已支付 | ✓ | 有行 · ¥3.50 |
| A-05 | Tab 争议中 | ✓ | 共 0 |
| A-06 | Tab 已退款 | ✓ | 共 1 · ¥0.00 / 已退 ¥3.50 · `ord-12-refunded.png` |
| A-07 | Tab 已取消 | ✓ | 共 0 |
| A-08 | 关键词（单号） | ✓ | 1 行 |
| A-09 | 关键词 SKU-DEMO | ✓ | 0（诚实空）· `ord-03-keyword.png` |
| A-10 | 重置 | ✓ | 回列表 |
| A-11 | 渠道=余额 | ✓ | URL `payChannel=BALANCE` |
| A-12 | 隐藏零元 开/关 | ✓ | 关后行数↑ |
| A-13 | 已退款+隐藏零元 | ✓ | **修后**仍共 1 条 · `ord-retest-refunded-hidezero.png`（#204） |
| A-14 | overdue 深链 | ✓ | Tab 待支付 · 勾选 · banner · 空态 · `ord-04-overdue.png` |
| A-15 | 查询 / 刷新 | ✓ | |
| A-16 | 列设置 | ✓ | popover · `ord-08-cols.png` |

## B 导出

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 按订单导出 | ✓ | `orders.csv` |
| B-02 | 按商品导出 | ✓ | `order-lines.csv` · `ord-05-export.png` |

## C 列表行 / 深链

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 订单号 → 抽屉 | ✓ | 单号/¥3.50/可乐 · `ord-06-detail.png` |
| C-02 | 行「详情」 | ✓ | 抽屉开 |
| C-03 | 行「退款」→ 取消 | ✓ | `ord-11-row-refund.png` |
| C-04 | 更多 ▾ | ✓ | 复制单号\|会话\|录像 |
| C-05 | 会话 link | ✓ | `/sessions?deviceId=…&sessionId=…` |
| C-06 | 设备 link | ✓ | `/devices/330449777078` · `ord-10-device.png` |
| C-07 | 分页 | ✓ | 可见；本页 next disabled（≤页大小） |

## D 抽屉动作

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| D-01 | 播放会话录像 | ✓ | toast「录像尚未上传或不存在」· `ord-09-play.png` |
| D-02 | 全额退款 → 取消 | ✓ | prompt/confirm 取消 |
| D-03 | 按行退款 → 取消 | ✓ | 对话框取消 · `ord-07-partial.png` |
| D-04 | 抽屉设备链 | ✓ | `/devices/330449777078` |
| D-05 | 催付 / 补扣 / 关单 | N/A | 演示环境 PENDING=0（非漏测） |

## E UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| E-01 | 900 无整页横滚 | ✓ | `ord-ux-narrow.png` |
| E-02 | 1366 / 1920 | ✓ | `ord-ux-1366.png` / `ord-ux-1920.png` |
| E-03 | API 落盘 | ✓ | `ord-api-list.json` |

## 结案

- [x] 可见可点控件全覆盖（PENDING 写操作为环境 N/A）
- [x] 资金确认均取消
- [x] #204 已退款+隐藏零元复测通过
|
