# 运营大屏 UAT · FINDINGS · 2026-09-26

> 工具：Playwright MCP + 微信开发者工具  
> 文档：[`BIG_SCREEN_FULL_BROWSER_UAT.md`](../../../uat/BIG_SCREEN_FULL_BROWSER_UAT.md)  
> Commit：`0b18a6dd`

---

## 结论摘要

| 维度 | 结果 |
|------|------|
| 顶栏按钮（刷新/全屏/退出/返回） | **PASS**（本轮全屏 API 成功） |
| KPI ×6 与 API | **PASS** |
| 地图/图表/排行/明细/待办 | **PASS** |
| 跨模块对账 | **PASS**（营收/争议/排行柜详情） |
| 三端口径 | **PASS**（营收 350¢ 大屏=商户 stats） |
| 缺陷 | **0 FAIL**；1 条口径说明（非缺陷） |

截图目录：`docs/uat-screenshots/2026-09-26/big-screen/`

---

## KPI ⇄ API

| UI | 值 | API |
|----|-----|-----|
| 售货机总数 | 1（在售 1） | deviceTotal=1 · devicesOnSale=1 |
| 今日营收 | ¥3.50 | revenueTodayCents=350 |
| 今日订单 | 2 | orderToday=2 |
| 今日毛利 | ¥1.60（45.7%） | grossMarginTodayCents=160 |
| 设备在线率 | 0.0%（投放离线 1） | sla.deviceOnlineRate=0 · offlineDevices=1 |
| 待处理争议 | 8（逾期 8） | openDisputes=8 · overdueDisputes=8 |
| 待办 | 前 6 · 共 15 | actionItems.length=15 |

---

## 口径说明（非 FAIL）

### P1 · 排行 ¥0 vs 今日营收 ¥3.50

| 现象 | 今日 KPI 营收 ¥3.50，点位排行仅「浏览器自动发号柜 ¥0.00」 |
| 根因 | 成交柜 `330449777078` 生命周期为 **INBOUND**；大屏排行/地图/投放 KPI **仅统计 DEPLOYED**（`777740024057`）。营收 stats 含全量订单。 |
| 必须怎么做 | 验收时不得要求「排行合计=今日营收」；投放口径以 lessons #199 / 大屏文案为准。若产品要排行含进件柜，需改需求而非当 bug 乱改。 |

### P2 · 离线列表「共 2」vs hint「投放离线 1」

| 现象 | `devices?online=OFFLINE` 共 2 条；大屏 hint=1 |
| 根因 | 设备列表 offline 筛选含非投放；大屏/工作台离线 hint 仅投放。 |
| 必须怎么做 | 对账用同口径（投放）；跨页数字允许因生命周期过滤不同。 |

---

## 三端

| 端 | 今日营收 | 离线 |
|----|----------|------|
| 运营大屏 | ¥3.50（350¢） | 投放离线 1 |
| 运营工作台 | ¥3.50 | 离线设备 1 |
| 商户 mp 工作台 | **今日营收 ¥3.50** · 商户收入 ¥3.15 · 在线 1/2 · **离线柜 1** | `bs-mp-m-home-live.png` |
| 商户 API | revenueTodayCents=**350** · deviceOffline=1 · total=2 | `bs-mp-m-stats.json` |

---

## UX

| 项 | 结果 |
|----|------|
| 1366 / 1920 无整页横滚 | PASS |
| 全屏进出 | PASS |
| 返回工作台 | PASS |

---

## 结案勾选

- [x] §2.1～2.4
- [x] §2.3 口径
- [x] §2.5 跨模块
- [x] §2.6 三端
- [x] §2.7 UX
- [x] BUTTONS.md / 本文
