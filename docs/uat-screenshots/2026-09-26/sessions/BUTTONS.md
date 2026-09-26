# 开门记录 · 按钮清点 · 2026-09-26

> `/sessions` · Playwright **1366×768**  
> [`SESSIONS_FULL_BROWSER_UAT.md`](../../../uat/SESSIONS_FULL_BROWSER_UAT.md)

---

## A 页头 / Tab / 筛选

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开页 | ✓ | `ses-01-home.png` · 共 57 = API |
| A-02～12 | Tab×11 | ✓ | `ses-02-tabs.png`（已完成 35 / 待审核 8 / 已取消 14 等） |
| A-13 | 关键词会话号 | ✓ | 1 行 · `ses-03-keyword.png` |
| A-14 | 类型=补货 | ✓ | **修后**共 0 + 空态 · `ses-retest-kind-restock.png`（#205） |
| A-15 | 类型=消费 | ✓ | 有行可翻页 · `ses-retest-kind-consumer.png` |
| A-16 | 重置 | ✓ | |
| A-17 | 仅滞留 / `?stuck=1` | ✓ | 共 0 · 无已完成泄漏 · `ses-04-stuck.png`（#195） |
| A-18 | 查询 / 刷新 | ✓ | |
| A-19 | 列设置 | ✓ | popover |
| A-20 | 分页下一页 | ✓ | next 可点 |

## B 导出

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 导出 | ✓ | `sessions.csv` · `ses-05-export.png` |

## C 行 / 抽屉

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 时间线 | ✓ | 创建→开门→关门→录像 · `ses-06-timeline.png` |
| C-02 | 播放录像 | ✓ | 有片会话开播（blob）；`ses-07-play.png` |
| C-03 | 录像上传队列 | ✓ | `/upload-queue?deviceId=330449777078` · `ses-08-upload-queue.png` |
| C-04 | 查看订单 | ✓ | `/orders?deviceId=330449777078` · `ses-09-orders.png` |
| C-05 | 设备链 / 看设备 | ✓ | `/devices/330449777078` · `ses-10-device.png` |
| C-06 | 取消会话 | N/A | 无活跃可取消行（非漏测） |

## D UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | 900 无整页横滚 | ✓ | `ses-ux-narrow.png` · vw=900 |
| D-02 | 1366 / 1920 | ✓ | `ses-ux-1366.png` / `ses-ux-1920.png` |
| D-03 | API 落盘 | ✓ | `ses-api-list.json` |

## 结案

- [x] 可见可点全覆盖（取消会话环境 N/A）
- [x] #195 滞留 / #205 类型 total 复测通过
|
