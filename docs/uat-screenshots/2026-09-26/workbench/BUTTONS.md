# 运营工作台 · 按钮清点 · 2026-09-26

> 工具：Playwright MCP · 账号 `13900000001` · 视口 1366×768  
> 文档：[`WORKBENCH_FULL_BROWSER_UAT.md`](../../../uat/WORKBENCH_FULL_BROWSER_UAT.md)  
> **小程序**：三端口径 **只认微信开发者工具 mp-weixin**；本表不含 H5 冒充 PASS。

---

## B.1 本页控件

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| D-01 | 补货调度 | ✓ | → `/replenishment?tab=routes` |
| D-02 | 争议审核 | ✓ | → `/disputes?status=OPEN` |
| D-03 | 设备管理 | ✓ | → `/devices` |
| D-04 | 设备可用性 | ✓ | → `/device-kpi` |
| D-05 | 刷新 | ✓ | 重载成功 |
| D-KPI-售 | 在售货柜 | ✓ | → `salesLocked=false` |
| D-KPI-在线 | 设备在线率 | ✓ | → DEPLOYED + OFFLINE |
| D-KPI-营收 | 今日营收 | ✓ | → `/finance`；造单后 UI ¥3.50 |
| D-KPI-异常 | 待处理异常 | ✓ | → `/exceptions?status=OPEN` |
| D-KPI-a11y | Enter/Space | ✓ | Enter→在售设备；Space→异常中心（WB-K-05） |
| D-Q 对账差异 | zone-link | ✓ | → reconciliation MISMATCH |
| D-Q 分账异常 | zone-link | ✓ | → merchants?tab=splits |
| D-Q 异常中心 | zone-link | ✓ | OPEN |
| D-Q 待审争议 | zone-link | ✓ | OPEN |
| D-Q 停售货柜 | zone-link | ✓ | salesLocked=true |
| D-Q 离线设备 | zone-link | ✓ | online=OFFLINE |
| D-extra | 财务/异常/设备/进件 | ✓ | 各落到目标页 |
| D-零显 | 分区显示 0 待办 | ✓ | 勾选后区链出现 0 项 |
| D-零-UI | 超时待支付/待上传/异常会话/补货/签收/缺货/临期 | ✓ | **从区卡点击**（非仅直链）均落地 |
| D-紧急 | 全部/仅紧急 | ✓* | 本轮全紧急，条数不变 |
| D-列设置 | 列设置 | ✓ | popover 显示 6/6 |
| D-查看 争议 | ✓ | 抽屉自动开 |
| D-查看 对账 | ✓ | reconciliation |
| D-查看 离线 | ✓ | `/devices/777740024057` |
| D-查看 分账 | ✓ | 第 2 页点「查看」→ `merchants?tab=splits` · `wb-x-split-from-pager.png` |
| D-分页 | ✓ | 共 15 条 · 10条/页 · 第 2 页可进 |

## B.2 零计数直链冒烟

| 目标 | 判定 |
|------|------|
| `/orders?status=PENDING&overdue=1` | ✓ 诚实空「无超时未付订单」 |
| `/upload-queue?stuck=1` | ✓ |
| `/stock-health?...` / 临期 | ✓ 诚实空「暂无异常库存」 |
| `/sessions?stuck=1` | ✓ 诚实空「当前无超过 30 分钟的滞留会话」 |
| `/warehouse?tab=transit&overdue=1` | ✓ 诚实空「当前无超过 24 小时的到柜超时」 |
| `/replenishment?tab=routes` | ✓ |

## B.3 跨模块深追（§2.6 全 7 条）

| ID | 判定 | 证据 |
|----|------|------|
| WB-X-01 待审争议 | ✓ | 抽屉 · `wb-x-01-dispute-drawer.png` |
| WB-X-02 超时待支付 | ✓ | 落地 + 诚实空 · `wb-x02-overdue-empty.png`（无单可对 mp，N/A） |
| WB-X-03 离线/停售 | ✓ | 设备详情 + 商户 mp 柜机 · 既有 `wb-mp-m-devices.png` |
| WB-X-04 异常会话 | ✓ | stuck 诚实空 · `wb-x04-stuck-empty.png`；任意会话时间线+有录像 · `wb-x04-session-timeline.png` |
| WB-X-05 缺货/补货 | ✓ | 库存健康诚实空 + 补货调度 · `wb-x05-*.png`（缺货=0，商户补货无可对 SKU） |
| WB-X-06 对账/分账 | ✓ | 对账差额 -¥3.00 · `wb-x06-recon.png`；分账 Tab ¥3.15/¥0.35/¥3.50 · `wb-x06-splits-amounts.png`；商户钱包 API available=2915¢、最近 SPLIT_CREDIT 315¢（差 0） |
| WB-X-07 签收超时 | ✓ | 在途+仅超时诚实空 · `wb-x07-transit.png` |

## B.4 三端（mp-weixin）

| 项 | 判定 | 证据 |
|----|------|------|
| 商户首页/柜机/争议 | ✓ | `wb-mp-m-home.png` · `wb-mp-m-devices.png` · `wb-mp-m-disputes.png` |
| 消费者+后台新单 ¥3.50 | ✓ | `wb-mp-c-new-order.png` + admin orders |
| 商户钱包/分账金额 | ✓ | API wallet 2915¢ / SPLIT_CREDIT 315¢ ↔ 后台分账 ¥3.15 |
| 本轮 DevTools 商户自动登录 | ⚠ | automator 停在 login；金额以 **API+既有截图** 为准，未再伪造成 UI PASS |

## B.5 缺陷收口

| # | 问题 | 判定 |
|---|------|------|
| D-分页 | total 错 | FAIL→已修→**复测 PASS** |
| D-履约角标 | 双计 | FAIL→已修→**复测 PASS**（20=12+8） |

## B.6 空态

| ID | 判定 |
|----|------|
| WB-Q-09 运行正常空态 | **SKIP**（actionItems=15，无法无写清空调；源码 `queueEmptyText` 已核对） |

## B.7 结案

- [x] §2.1～2.5 本页控件（含零计数 **UI 点击** + KPI 键盘）
- [x] §2.6 跨模块 7/7
- [x] §2.7 三端口径（订单/设备/分账金额；mp 只认 DevTools）
- [x] §2.8 UX + 截图
- [x] 分页/履约角标静态复测
- [x] BUTTONS / FINDINGS 已更新
