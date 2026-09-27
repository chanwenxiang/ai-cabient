# S3 · 附录 A 页矩阵（mp-weixin · 2026-09-27）

> 权威：**微信开发者工具 + automator `:9420`** · 禁止 H5 冒充 PASS  
> 前置：API 基址已修为 `http://127.0.0.1`（lesson **#226**）；nginx `/consumer|/merchant` 仍 302→admin（不测）  
> 账号：消费者 `13800138000` · 商户 `13800138001` · 柜 `166813762350` · 同单 `1790493168114576060218`（¥2.50 券后）  
> 软写：仅导航截图；未硬充/硬提现/补货 complete/申诉提交

## 总判定

| 端 | 判定 | 层 | 说明 |
|----|------|----|------|
| 消费者 mp | **PASS** | L1 全页 + 多页 L2 | 22 路由可达；余额 ¥197.50；订单/充值有数；视频诚实失败 |
| 商户 mp | **PASS** | L1 全页 + 多页 L2 | 22 路由可达；登录「补货与运营」≠ 消费端；工作台/订单/定价有数 |
| device-detail 复测 | **PASS** | L2 | 根因：query 须 `id=` 非 `deviceId=`；柜 `166813762350` 在线 · `s3-m-device-detail-recheck.png` |
| 仓配采购/调拨单号 | **PASS**（软） | L2 | 采购单列 **4**；调拨号 **1790507082502743938921** 纯数字后 cancel |
| S3-B2 兑换写路径 | **PASS**（软） | L2 | 项#4 INACTIVE（须带 couponDefId）；`s3-b2-points-redeem-soft.png` |
| L3 全量 | **SKIP（本轮不做）** | — | 价值链 S2/S3 Admin 已验 |

JSON：`s3-consumer-appendix.json`（末三段续扫）· `s3-merchant-appendix.json`（全量）

---

## 消费者附录 A.1

| path | 判定 | 层 | 证据 / 备注 |
|------|------|----|-------------|
| login | PASS | L1 | `s3-c-login.png` · 扫码购物文案 |
| index | PASS | L1 | `s3-c-index.png` · wait≥3.5s（#211） |
| orders | PASS | **L2** | 有 ¥2.50 / ¥3.50 等 |
| order-detail | PASS | **L2** | 同单已支付 · 券抵后 ¥2.50 |
| video | PASS | L1 | **诚实失败**：下载失败（404） |
| dispute/detail | PASS | L1 | 「缺少审核单参数」诚实空（未带 disputeId） |
| mine | PASS | **L2** | 可用余额 **¥197.50** · 可开门 · 优先支付=余额 |
| balance | PASS | **L2** | ¥197.50 + 流水（充值/扣款） |
| recharge | PASS | **L2** | 余额一致 · 记录已支付 · **未硬充** |
| verify | PASS | L1/L2 | 账户余额可见 |
| result | PASS | L1 | 「缺少订单信息」诚实空 |
| marketing | PASS | L1 | 领券文案可见 |
| member | PASS | L1/L2 | 普通会员档 |
| points / redeem | PASS | L1 | 兑换空态诚实 |
| coupons | PASS | L1/L2 | 券列表 Tab |
| messages / announcements | PASS | L1 | 可达 |
| report / feedback | PASS | L1 | 类型选项可见 · **未提交** |
| help / policy | PASS | L1 | 静态 FAQ / 协议文案 |

---

## 商户附录 A.2

| path | 判定 | 层 | 证据 / 备注 |
|------|------|----|-------------|
| login | PASS | L1 | **「补货与运营」** · `s3-m-login.png` / `s3-m-login-idcheck.png` |
| home | PASS | **L2** | 今日营收 ¥6.00 · 商户收入 ¥5.40 · 待办 2 |
| alerts | PASS | L1/L2 | 含消费者柜机报修类待办 |
| devices | PASS | L1/L2 | 在线/离线 Tab |
| device-detail | **PASS** | **L2** | 复测 `?id=` · 在线/货道/设置可见（首轮误用 `deviceId=`） |
| replenishment | PASS | L1 | 签到/开门流程文案 · **未 complete** |
| orders / order-detail | PASS | **L2** | 同单 ¥2.50 已支付 · 纯牛奶/货道 |
| video | PASS | L1 | 下载失败诚实 |
| wallet | PASS | L1 | 可达（软写未点提现确认） |
| splits | PASS | L1 | 可达 |
| settlements | PASS | **L2** | 分账模拟说明 + 金额 |
| disputes | PASS | L1 | Tab 待审核/已结案 |
| request / business | PASS | L1/L2 | 要货建议柜+SKU；分析有营收 |
| pricing | PASS | **L2** | 演示柜 ¥3.50 等 · **未改价确认** |
| team | PASS | L1 | 角色文案可见 |
| line-wallet | PASS | L1 | 可达 |
| messages / announcements | PASS | L1 | 可达 |
| mine | PASS | L1/L2 | 默认商户管理员 · S1演示商户 |
| policy (privacy) | PASS | L1 | 条款页可达 |

---

## 仓配 / 兑换软写（续）

| 项 | 结果 |
|----|------|
| 采购单 API create → review reject | purchaseOrderId **4** · 状态 **已驳回**；Admin「采购单」列显示 **4**（纯数字）· 外部单号 `UAT-S3-PO-190311` |
| 调拨 | 新建仓 `493343219647` → transferNo **`1790507082502743938921`** · DRAFT→**CANCELLED** |
| 积分兑换 | PUT 须 `couponDefId`（缺则 DB NOT NULL→500）；项 **#4** · 9999 分 · **停用** |

截图：`s3-wh-purchases-soft.png` · `s3-wh-transfers-soft.png` · `s3-b2-points-redeem-soft.png`

## FINDING

| # | 严重度 | 现象 | 处置 |
|---|--------|------|------|
| F1 | P2 | 购物视频双端「下载失败」 | 与 mp-smoke F1 同源；诚实 L1 PASS |
| F2 | P3 | 商户柜机详情 automator 用 `deviceId=` 一直「加载中」 | **必须** `?id=`（页内读 `opts.id`）；已复测 PASS |
| F3 | 信息 | automator `navigateBack` 易挂 | 扫页脚本改全程 `reLaunch` |
| F4 | P3 | 积分兑换 upsert 不传 `couponDefId` → 500 | Admin/API 建兑换项必须带券定义 ID |

## 相对价值链进度

| 阶段 | 状态 |
|------|------|
| S0–S2 | 早轮已 PASS（本轮未重跑） |
| S3 Admin/API | 早轮基本收口 |
| **附录页矩阵 mp L1** | **PASS** |
| **仓配采购/调拨单号** | **PASS**（软） |
| **S3-B2 兑换写路径** | **PASS**（软） |
| 文档 commit | 待用户明示 |

S3 续测缺口（提示词列）已基本扫完。
