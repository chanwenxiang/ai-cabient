# 运营大屏 · 按钮清点 · 2026-09-26

> 工具：Playwright MCP · 账号 `13900000001` · 视口 1366×768 + 抽检 1920×1080  
> 文档：[`BIG_SCREEN_FULL_BROWSER_UAT.md`](../../../uat/BIG_SCREEN_FULL_BROWSER_UAT.md)  
> **小程序**：三端口径只认 DevTools mp-weixin

---

## B.1 顶栏控件

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| D-01 | 刷新 | ✓ | loading 时 disabled；`bs-04-after-refresh.png` |
| D-02 | 全屏 | ✓ | `fullscreenElement` 进入；文案→退出全屏 |
| D-03 | 退出全屏 | ✓ | 退出成功；无侧栏残留 |
| D-04 | 返回后台 | ✓ | → `/dashboard` · `bs-return-dashboard.png` |

## B.2 KPI（展示，不可点）

| # | KPI | UI | API | ✓ |
|---|-----|-----|-----|---|
| K1 | 售货机总数 | 1 · 在售 1 | stats.deviceTotal=1 · devicesOnSale=1 | ✓ |
| K2 | 今日营收 | ¥3.50 · 累计 ¥33.50 | revenueTodayCents=350 | ✓ |
| K3 | 今日订单 | 2 · 累计 24 | orderToday=2 | ✓ |
| K4 | 今日毛利 | ¥1.60 · 45.7% | grossMarginTodayCents=160 | ✓ |
| K5 | 设备在线率 | 0.0% · 投放离线 1 | sla 0 · offlineDevices=1 | ✓ |
| K6 | 待处理争议 | 8 · 逾期 8 | openDisputes=8 | ✓ |

点击 KPI 不跳转（`href` 不变）✓

## B.3 地图 / 图 / 列表

| # | 区 | 判定 | 证据 |
|---|-----|------|------|
| M-01 | 地图图例 | ✓ | 点位在线 0 / 离线 1 · 有坐标 1/1 |
| C-01～03 | 趋势/品类/区域 | ✓ | 面板标题可见；有图或空态 |
| L-01 | 点位排行 | ✓ | Top1 浏览器自动发号柜 ¥0.00（仅投放） |
| L-02 | 运营明细 | ✓ | 同柜 · 离线 |
| L-03 | 待办/告警 | ✓ | **前 6 · 共 15** |

## B.4 跨模块对账

| ID | 判定 | 证据 |
|----|------|------|
| BS-X-01 营收 vs 工作台 | ✓ | 同 350¢ |
| BS-X-02 争议 vs 列表 | ✓ | openDisputes=8 · 共 8 条 · `bs-x02-disputes.png` |
| BS-X-03 离线 | ✓* | 投放离线 hint=1；`devices?online=OFFLINE` 共 2 条（含非投放）· 口径差见 FINDINGS |
| BS-X-04 排行柜详情 | ✓ | `/devices/777740024057` 投放·离线·已锁机 |

## B.5 三端（mp）

| 项 | 大屏 | 商户 | 判定 |
|----|------|------|------|
| 今日营收 | 350¢ | `merchant/stats` revenueTodayCents=350 | ✓ |
| 离线柜 | hint 1（投放） | stats deviceOffline=1 · 柜机列表 | ✓（分母：商户 total=2） |
| 争议 | 8 | 既有争议页抽样 | ✓ 语义 |

截图：`bs-mp-m-home.png` / `bs-mp-m-devices.png` / `bs-mp-m-stats.json`；现场 `pages/home/home` 已打开。

## B.6 UX

| ID | 判定 |
|----|------|
| 1366×768 无整页横滚 | ✓ `bs-ux-1366.png` |
| 1920×1080 | ✓ `bs-ux-1920.png` |
| 暗色驾驶舱 / 中文 | ✓ |

## B.7 结案

- [x] 本页按钮全点
- [x] KPI 口径 vs API/工作台
- [x] 跨模块 ≥2（实做 4）
- [x] 三端营收/离线
- [x] UX 双视口
- [x] BUTTONS / FINDINGS
