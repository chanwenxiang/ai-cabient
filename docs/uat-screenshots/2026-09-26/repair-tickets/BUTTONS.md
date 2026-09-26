# 维修工单 · 按钮清点 · 2026-09-26

> `/repair-tickets` · Playwright **1366×768**  
> [`REPAIR_TICKETS_FULL_BROWSER_UAT.md`](../../../uat/REPAIR_TICKETS_FULL_BROWSER_UAT.md)

---

## A 页头 / 列表

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 首页列表 | ✓ | 共 1 · 已完成 · `rep-01-home.png` |
| A-02 | 状态中文 | ✓ | 「已完成」非 DONE |
| A-03 | 批量指派（未勾选） | ✓ | disabled |
| A-04 | 查询 / 刷新 | ✓ | `rep-14-refresh.png` |

## B 筛选

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 状态=已完成 | ✓ | 共 1 · `rep-03-status-done.png` |
| B-02 | 状态=待处理 | ✓ | 共 0 + 暂无维修工单 · `rep-04-status-open.png` |
| B-03 | 设备=发号柜 | ✓ | 共 1 · `rep-05-device.png` |
| B-04 | `?deviceId=` 深链 | ✓ | 回显发号柜 · `rep-06-deeplink.png` |
| B-05 | 故障类型=门锁 | ✓ | 共 1 · `rep-07-fault.png` |
| B-06 | 优先级=普通 | ✓ | 共 1 · `rep-08-priority.png` |

## C 详情 / 深链

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 详情抽屉 | ✓ | 工单号 1 · 流转 3 · `rep-09-detail.png` |
| C-02 | 设备名链接 | ✓ | `/devices/777740024057` · `rep-10-device-link.png` |

## D 写路径（取消）

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| D-01 | 新建工单 → 取消 | ✓ | `rep-11-create.png` |
| D-02 | 勾选 → 批量指派 → 取消 | ✓ | `rep-12-assign.png` |
| D-03 | 开始处理/完成流转 | — | 本环境仅 DONE 行；无写入口（非 FAIL） |

## E UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| E-01 | 900 无整页横滚 | ✓ | `rep-ux-narrow.png` |
| E-02 | 1366 / 1920 | ✓ | `rep-ux-1366.png` / `rep-ux-1920.png` |

## 结案

- [x] 筛选/深链/详情/软写取消覆盖；视口结束 1366
|
