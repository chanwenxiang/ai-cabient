# 数据分析 · 按钮清点 · 2026-09-26

> 工具：Playwright MCP · 账号 `13900000001` · 视口 1366×768 + 抽检 1920×1080  
> 文档：[`ANALYTICS_FULL_BROWSER_UAT.md`](../../../uat/ANALYTICS_FULL_BROWSER_UAT.md)  
> **小程序**：三端口径只认 DevTools mp-weixin

---

## B.1 顶栏

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| D-01 | 刷新 | ✓ | KPI 刷新前后均为 ¥3.50 / 2 / 100.0% / 100.0% |

## B.2 KPI 深链

| # | KPI | UI | API | 深链落地 | ✓ |
|---|-----|-----|-----|----------|---|
| K1 | 今日营收 | ¥3.50 · 查看财务毛利 | revenueTodayCents=350 | `/finance` 财务毛利 | ✓ |
| K2 | 今日订单 | 2 · 查看订单列表 | orderToday=2 | `/orders` 订单管理 | ✓ |
| K3 | 24h 开门成功率 | 100.0% · 查看开门记录 | doorSuccessRate24h=1.0 | `/sessions` 开门记录 | ✓ |
| K4 | 24h 自动识别率 | 100.0% · 查看争议审核 | recognitionAutoRate24h=1.0 | `/disputes`（本环境标签记忆带 `?status=OPEN`） | ✓ |

截图：`an-k1-finance.png` … `an-k4-disputes.png`

## B.3 趋势 / 图型 / 侧栏

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| R-01 | 今天 / 近7 / 30 / 90 | ✓ | URL `?days=` 同步；文案「今天」非「近 1 天」；**KPI 全程不变** |
| C-营收 | 折线/面积/柱 ×3 | ✓ | 3 组 fieldset 全切无报错 |
| C-订单 | 同上 | ✓ | |
| C-识别 | 同上 | ✓ | |
| 渠道 | 订单支付 / 充值 | ✓ | 余额 ¥3.50（2 单）；充值「暂无充值数据」 |
| S-离线 | 查看离线 1 | ✓ | → `/devices?online=OFFLINE` · 离线 Tab 2（含非投放） |
| S-争议 | 8 条待审 | ✓ | → `/disputes?status=OPEN` · 共 8 条 |
| S-毛利 | 查看 45.7% | ✓ | → `/finance` · 今日毛利率 45.7% |

## B.4 跨模块对账

| ID | 判定 | 证据 |
|----|------|------|
| AN-X-01 营收 vs 工作台 | ✓ | 工作台「今日营收 ¥3.50」· `an-x1-dashboard.png` |
| AN-X-01b vs 财务 | ✓ | 财务今日营收 ¥3.50 / 毛利率 45.7% |
| AN-X-02 待审争议 | ✓ | disputeOpen=8 · 列表共 8 条 · `an-x2-disputes.png` |
| AN-X-03 离线 | ✓* | 分析侧栏离线 **1**（stats 投放分母）；设备离线筛选 **2** · 口径见 FINDINGS |

## B.5 三端（mp）

| 项 | 后台分析 | 商户 mp | 判定 |
|----|----------|---------|------|
| 今日营收 | ¥3.50 (350¢) | home `t=¥3.50`；API revenueTodayCents=350 | ✓ |
| 离线 | 投放离线 1 | home `A=1/2`；stats deviceOffline=1 | ✓（商户分母 2） |
| 争议 | 8 | 待办「待审核争议 共 8 件」 | ✓ |

截图：`an-mp-m-home.png` · `an-mp-m-probe.json`

## B.6 UX

| ID | 判定 |
|----|------|
| 1366×768 无整页横滚 | ✓ scrollWidth=clientWidth · `an-ux-1366.png` |
| 1920×1080 | ✓ · `an-ux-1920.png` |
| 中文 / 空态诚实 | ✓ 充值「暂无充值数据」 |

## B.7 结案

- [x] 本页按钮/KPI/图型全点
- [x] KPI 口径 vs API
- [x] 深链落地 KPI×4 + 侧栏×3
- [x] 跨模块 ≥2（实做 4）
- [x] 三端营收/离线/争议
- [x] UX 双视口
- [x] BUTTONS / FINDINGS
|
