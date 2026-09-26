# 设备管理 · 按钮清点 · 2026-09-26

> `/devices` · Playwright **1366×768**  
> [`DEVICES_FULL_BROWSER_UAT.md`](../../../uat/DEVICES_FULL_BROWSER_UAT.md)  
> 写路径：确认框/弹层 → **取消**

---

## A 看板 / Tab

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 首页 | ✓ | 共 3 = API · 看板 3/1/2/0/0/3 · `dm-01-home.png` |
| A-02 | Tab 在线 | ✓ | `?online=ONLINE` · 共 1 · `dm-02-online.png` |
| A-03 | Tab 离线 | ✓ | 共 2 · `dm-03-offline.png` |
| A-04 | Tab 可购买 | ✓ | 共 0（全锁机）· `dm-04-canbuy.png` |
| A-05 | Tab 已锁机 | ✓ | 共 3 · `dm-05-locked.png` |
| A-06 | Tab 全部 | ✓ | 共 3 |
| A-07 | 看板「离线」 | ✓ | → OFFLINE · `dm-07-board-offline.png` |

## B 筛选 / 深链

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | `lifecycleStatus=DEPLOYED&online=OFFLINE` | ✓ | 共 1 · 777740024057 · UI「投放」· `dm-08-deep-link.png` |
| B-02 | 关键词 CAB-001 | ✓ | 共 1 · `dm-10-keyword.png` |
| B-03 | 重置 | ✓ | |

## C 批量 / 新建（软写）

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 批量解锁 → 取消 | ✓ | `dm-11-batch-unlock.png` |
| C-02 | 批量锁机 → 取消 | ✓ | `dm-12-batch-lock.png` |
| C-03 | 批量投放 → 取消 | ✓ | `dm-13-batch-deploy.png` |
| C-04 | 批量退役 → 取消 | ✓ | `dm-14-batch-retire.png` |
| C-05 | 新建设备 → 取消 | ✓ | `dm-15-create.png` |
| C-06 | 未勾选时批量 | ✓ | disabled 合理 |

## D 行 / 工具

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| D-01 | 详情 | ✓ | `/devices/330449777078` · `dm-16-detail.png` |
| D-02 | 退款设置 → 取消 | ✓ | `dm-17-policy.png` |
| D-03 | 导出 | ✓ | `设备_20260926_*.csv` |
| D-04 | 刷新 / 列设置 | ✓ | |

## E UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| E-01 | 中文状态 | ✓ | 在线/离线/已锁机/入库/投放 |
| E-02 | 900 无整页横滚 | ✓ | `dm-ux-narrow.png` |
| E-03 | 1366 / 1920 | ✓ | `dm-ux-1366.png` / `dm-ux-1920.png` |

## 结案

- [x] 看板/Tab/深链/导出覆盖
- [x] 写路径均取消；视口结束 1366
|
