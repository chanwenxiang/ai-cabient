# 补货调度 · 按钮清点 · 2026-09-26

> `/replenishment` · Playwright **1366×768**  
> [`REPLENISHMENT_FULL_BROWSER_UAT.md`](../../../uat/REPLENISHMENT_FULL_BROWSER_UAT.md)

---

## A 头 / KPI / 路线

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 首页 KPI | ✓ | 待执行3/待处理0/已履约3/要货待审0 · `rep-01-home.png` |
| A-02 | 路线列表 | ✓ | 共 6 · 中文状态 |
| A-03 | 展开路线 | ✓ | 签到中文 · `rep-02-expand.png` |
| A-04 | 规划补货路线 → 取消 | ✓ | `rep-03-plan.png` |
| A-05 | 取消空路线 → 取消 | ✓ | `rep-04-cancel-route.png` |
| A-06 | 导出 | ✓ | 可见 |

## B Tab

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 履约记录 | ✓ | 筛「仅待分配」· `rep-05-fulfillment.png` |
| B-02 | 要货·待审核 | ✓ | 共 0 · `rep-06-requests.png` |
| B-03 | 要货·全部 | ✓ | 共 1 已接单 · `rep-06b-requests-all.png` |
| B-04 | 要货·已接单 | ✓ | 共 1 · `rep-06c-accepted.png` |
| B-05 | 审批流抽屉 | ✓ | 查看补货任务 · `rep-07-request-flow.png` |
| B-06 | 缺货建议 | ✓ | 共 9 · `rep-08-shortage.png` |
| B-07 | 一键规划 → 取消 | ✓ | `rep-09-shortage-plan.png` |
| B-08 | 缺货→设备 | ✓ | `/devices/777740024057` · `rep-10-device.png` |
| B-09 | 临期下架 | ✓ | 共 0 · `rep-11-expiry.png` |

## C 深链

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | `plan=1&deviceIds=` | ✓ | 弹层后取消（#203）· `rep-12-deeplink-plan.png` |
| C-02 | `deviceId=` chip | ✓ | `rep-13-device-focus.png` |

## D UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | 900 无整页横滚 | ✓ | `rep-ux-narrow.png` |
| D-02 | 1366 / 1920 | ✓ | `rep-ux-*.png` |
| D-03 | 结束视口 1366 | ✓ | `rep-99-end.png` |

## 结案

- [x] FAIL 0；软写均取消；视口结束 1366
|
