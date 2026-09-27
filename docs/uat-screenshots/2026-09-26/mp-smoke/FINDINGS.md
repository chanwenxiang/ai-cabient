# 双端小程序基础烟测 · FINDINGS · 2026-09-27

> **权威**：mp-weixin（DevTools CLI + automator `:9420`）  
> **禁止**：H5 `:3001`/`:3002` 冒充 PASS（本轮未启 H5）  
> **姿态**：软写（充值/提现/补货完成/申诉提交均未硬提交）  
> **分层**：L1 壳 / L2 有数 / L3 三端（本轮不做全量 L3）  
> **证据目录**：本目录 `c-*.png` / `m-*.png` + `consumer-smoke.json` / `merchant-smoke.json`  
> **提示词**：[`docs/uat/MP_SMOKE_SESSION_PROMPT.md`](../../uat/MP_SMOKE_SESSION_PROMPT.md)

## 0. 环境

| 项 | 值 |
|----|-----|
| 日期 | 2026-09-27 |
| 栈 | 全栈 Docker UP（trade `:18080` / gateway `:80`） |
| 消费者 dist | `clients/consumer-mp/dist/dev/mp-weixin`（`urlCheck=false` · appid `wx5a5bc7b541b62a13`） |
| 商户 dist | `clients/merchant-mp/dist/dev/mp-weixin`（同上；merchant 无 `build:mp-weixin:dev`，本轮用 `uni build --mode development` + sync/inject） |
| 账号 | 消费者 `13800138000` · 商户 `13800138001` |
| 演示柜 | `330449777078`（列表可见；勿用 CAB-001） |
| CLI | `D:\devTools\微信web开发者工具\cli.bat` · 切换工程 `close`→`open`→`auto --auto-port 9420` |

## 1. 总判定

| 端 | 判定 | 层 | 说明 |
|----|------|----|------|
| 消费者 mp | **PASS** | **L1 全页 + 多页 L2** | 19 路由可达；余额/订单/优惠券有数；视频诚实 404 |
| 商户 mp | **PASS** | **L1 全页 + 多页 L2** | 19 路由可达；登录文案含「补货与运营」≠ 消费者；钱包/工作台/柜机/补货有数 |
| L3 全量 | **SKIP（本会话不做）** | — | 留给后续场景清单 + 脚本会话 |

同单抽样（非全量 L3）：orderId `1790420215052990851425` · 消费/商户详情均 **¥3.50 / 已支付 / 柜机 330449777078**。

## 2. 消费者清单

| ID | 路径 | 判定 | 层 | 证据 / 备注 |
|----|------|------|----|-------------|
| C-login | `/pages/login/login` | PASS | L1 | 首轮截图过早白屏 → 复测 `c-login-recheck.png` OK（lessons #211） |
| C-home | `/pages/index/index` | PASS | L1 | 首轮 `c-c-home.png` 白屏误判；`c-home-recheck2.png`：AI开门柜/扫码购物 |
| C-orders | `/pages/orders/orders` | PASS | **L2** | 共 23；首单 ¥3.50 已支付；柜机码展示区含 `330449777078` |
| C-order-detail | 同单详情 | PASS | **L2** | `c-order-detail.png` 实付 ¥3.50 · 订单号一致 |
| C-video | 购物视频 | PASS | L1 | **诚实失败**：视频加载失败 / 下载失败 (404)；未白屏 |
| C-dispute | 账单审核 | PASS | L1 | 已进页，**未提交申诉** |
| C-mine | 我的 | PASS | **L2** | 可用余额 **¥193.00**；优先支付=余额；可开门 |
| C-balance | 余额明细 | PASS | **L2** | ¥193.00 + 流水含本单 −¥3.50 |
| C-recharge | 账户充值 | PASS | **L2** | 当前余额 ¥193.00；记录 `1789658820257378119` 已支付；**未点微信/确认充值** |
| C-marketing / member / points / redeem | 活动/会员/积分 | PASS | L1–L2 | 页可开（截图见目录） |
| C-coupons | 优惠券 | PASS | **L2** | 3 张「完整轮满减券」¥0.50 已使用 |
| C-messages / announcements / help / report / feedback / policy | 消息等 | PASS | L1 | 可开；软写未提交报修/反馈 |
| C-verify / result | 开通支付/账单结果 | PASS | L1 | 可达 |

## 3. 商户清单

| ID | 路径 | 判定 | 层 | 证据 / 备注 |
|----|------|------|----|-------------|
| M-login | 登录 | PASS | L1 | **「补货与运营」** — 确认非消费者工程 |
| M-home | 工作台 | PASS | **L2** | 待办 18；近7日客单 ¥1.40；在线柜 1/2；今日补货空态诚实 |
| M-devices | 柜机 | PASS | **L2** | 全部 2；`330449777078` 在线；另一柜离线 |
| M-device-detail | 详情 | SKIP | — | 本轮 `/merchant/devices` probe `count=0`（与列表 UI 不一致，待下轮手点卡片）；列表本身 L2 |
| M-alerts | 待办 | PASS | L1/L2 | Tab 角标 18 |
| M-replenishment | 补货任务 | PASS | **L2** | 待处理 0 / 已完成 3；任务 #16 柜 `330449777078`；**未 complete** |
| M-request / business / settlements / disputes | 要货/分析/结算/争议 | PASS | L1 | 页可开 |
| M-orders / order-detail | 柜机订单 | PASS | **L2** | 同单 `1790…851425` ¥3.50 已支付 |
| M-video | 购物视频 | PASS | L1 | 截图 `m-video.png`（与消费端同源会话风险：404 诚实失败） |
| M-splits | 分账明细 | PASS | L1 | 落在「失败」Tab：**暂无分账异常**（诚实空）；「全部」未本轮手切 |
| M-wallet | 商户钱包 | PASS | **L2** | 可用 **¥29.15**；提现入口可见；流水含分账入账本单；**未点申请提现确认** |
| M-line-wallet | 线长钱包 | PASS | L1 | 可达（角色可见性未做权限矩阵） |
| M-pricing / team | 点位定价/团队 | PASS | L1 | 页可开；写操作未确认提交 |
| M-messages / announcements / policy / mine | 消息等 | PASS | L1 | 可开 |

## 4. FINDING / 风险

| # | 严重度 | 现象 | 处置 |
|---|--------|------|------|
| F1 | P2 | 购物视频 404（消费端明确「下载失败 (404)」） | 诚实失败算 L1 PASS；媒体 URL/对象存储下轮查 |
| F2 | P3 | automator 首页首帧白屏 | 已记 lessons **#211**；以 `c-home-recheck2.png` 为准 |
| F3 | P3 | 商户 devices API probe `count=0` 但 UI 列表有 2 柜 | 路径/鉴权字段与列表页不一致；下轮手点进详情 |
| F4 | 信息 | wxml/`page.data` 在 minify 下键名混淆、wxml 常空 | **以截图金额/文案为准**（同 recharges 回补口径） |

## 5. 软写声明

本轮**未**执行：硬充值、硬提现、补货 complete、申诉提交、改价确认、团队写确认。

## 6. 下一会话

1. ~~场景清单~~ → [`MP_THREE_END_SCENARIOS.md`](../../uat/MP_THREE_END_SCENARIOS.md)（46 条）  
2. ~~造数闸门~~ → `scripts/mp-seed-gate.ps1`（本机已 `pass=true`）  
3. **待做**：按 P0 顺序写三端自动化（Admin Playwright + mp automator）
