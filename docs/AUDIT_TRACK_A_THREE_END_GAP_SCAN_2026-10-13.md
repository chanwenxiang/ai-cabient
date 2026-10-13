# Track A-4/A-5 — THREE_END 11×P1 + GAP_SCAN G1–G8 复扫（2026-10-13）

基线 worktree `9911a2ee`。

## 表一 · THREE_END_FULL_REVIEW_2026-09-27 的 11 条 P1：**全部已修**（逐条取证）

| # | 项 | 结论 | 证据 |
|---|----|------|------|
| F1 | 结算竞态多付 | 已修 | `OrderPaymentService.java:375-387`（应收=total−netCompletedCents 含已冲抵预授权）；`UnpaidOrderService.java:340-342/379-382` |
| F2 | 购物超时免单 | 已修 | `SessionExpireService.java:438-481`（释放预授权→SHOPPING→DISPUTED→createTimeoutTicket，失败回退 CANCELLED） |
| F3 | 提现盲置 FAILED | 已修（单点化） | `WithdrawPayoutPolicy.mayAutoFailOnPayingTimeout:7,19` 唯一真源；`MerchantWithdrawService.java:651-665` 真实渠道转人工留痕；`LineWithdrawService.java:484` 同闸；`:634` 单位改 MINUTES 名实相符 |
| S1 | 设备共享 MQTT 凭据 | 已修（逐设备） | `V290__device_mqtt_credential.sql` + `DeviceMqttCredentialService/Controller`；`aicabinet-acl.conf:7-17` username 命名空间+deny；`aicabinet-device` 仅 dev 栈；生产 TLS 门禁（9ad741d1）+`MQTT_CLIENT_ID` 必填（3240b2e9）。APK 内嵌口令仅剩 dev 默认值、生产经 SharedPreferences 覆盖（`build.gradle.kts:18-21`）⇒ P3 尾巴 |
| S2 | GET ?access_token= | 已修（删除） | `auth/AuthInterceptor.java:37` 注释「已移除后备」，无消费点 |
| S3 | CSV 公式注入 | 已修 | `support/CsvCells.java`（`=+-@`+TAB/CR 中和）+`CsvCellsTest`，6 个导出服务接入 |
| S4 | full 栈 Grafana 裸奔 | 已修 | `nginx-full.conf:149-154` auth_basic + `docker-compose.full.yml:363` 绑 `127.0.0.1:13000` + 匿名 Viewer 关(:360) |
| A1 | 充值退款状态绕契约 | 已修 | `money-ui-contracts.ts:30 canRefundRechargeStatus`，`RechargeListView.vue:159,207` 复用 |
| C1 | 开门重试不分类 | 已修 | `consumer-api.ts:690` 仅 `isRetriableMpTransportError`（超时/网络）重试 |
| C2 | 首页轮询无上限 | 已修 | `landing-session.ts:44-47 SESSION_POLL_MAX_DURATION_MS=10min`；`use-session-poll.ts:77` 消费+单测 |
| X1 | preflight 再生盲区 | 已修 | `pre-push-ci-preflight.mjs:197-207` openApiSurface 时强制 `check:openapi-types` |
| X2 | staging mock 纵深 | 已修 | `docker-compose.staging.yml:28-39` 显式钉死 5 个 mock 开关 `:-false` |

二轮 V1–V4 同批确认：对账调度默认开（`application.yml:177`）、网关对齐、端口回环、MinIO anonymous none ✓。

## 表二 · GAP_SCAN G1–G8

| # | 结论 | 要点 |
|---|------|------|
| **G1** | **未修，P1（挂「接真实支付」腿；余额-only 档非上线阻塞）** | 现状中间态两类：① createRefund 返回 PROCESSING——有 `[refund:STATUS]` 痕迹，次日 `WeChatRefundReconciler` 可兜（RECON 调度已默认开）；② **createRefund 抛错/超时结果未知——DB 零痕迹**（`OrderPaymentService.java:472-498` prepare 短事务对 live 渠道不落任何行），reconciler 无行可扫。`WECHAT_PAY_ENABLED` 默认 false ⇒ live 分支当前不可达。最小 job：prepare 先落 `REFUND_INITIATED` 痕迹（对照既有 `CHARGE_INITIATED`）+ XXL handler（仿 `profitSharingRetry` 退避 60/240/540/960s×5）扫超 15min 的 INITIATED/`[refund:PROCESSING]` 调 `queryRefund` 收敛到 `finalizeLiveOrderRefund`（幂等键=out_refundNo 天然防重），耗尽发 `WECHAT_REFUND_STUCK` OpsAlert |
| G2 | 部分修，**P3** | admin `SalesReportsView.vue:160-190` 已单列退款/退款率/净营收 ✓；merchant-mp 首页退款单计数 ✓；merchant `business` 页有数据无渲染（`business-display.ts:132-133` 字段存在、页面零消费）⇒ 纯展示缺口，口径本身一致 |
| G3 | 已修（形态变化，非缺陷） | CB-034/V342 `DeviceRentalService` 收据化闭环（登记→扣款抵扣→settle 返还+事件审计+欠租锁柜），类注 :32-43 明确「押金是负债不进资金池、收缴/返还线下动作、不抄旧按月自动返还+奖励 200 元」⇒ 线上「审批+打款」仍无属**有意设计**；余额-only 档零资金动作。线上化返还可立项（P3） |
| G4–G8 | 维持原判（不抄/延后） | 卡体系/电子签/广告月租/设备转移/交通卡 grep 均无翻转迹象 |

## 接真 checklist 现状（THREE_END §6 对现状）

| 外部项 | 状态 |
|--------|------|
| 小程序正式号 | 仍缺（外部）；工程侧就绪（inject release fail-closed、`WECHAT_MINIAPP_ENABLED` 默认 false+生产强制声明 fe1f1766；订阅模板未配=静默跳过属既定设计）；前置 C1/C2 已修 ✓ |
| 微信商户号/支付 | 仍缺（外部）；「显式关支付」档已支持（ee9c56a2）；前置 F1 已修 ✓；**新增前置：G1 未修** |
| 真实硬件（MQTT/EMQX） | 部分具备：S1 逐设备凭据+TLS 门禁已落地；`MQTT_ALLOW_TCP` 为过渡豁免 |
| 将邑真机 | 代码就绪、**部署未就绪**：见下方 D1/D2 |

## 本轨道新增未修项（级别汇总）

- P0：无。
- **P1**：G1（接支付腿前置）。
- **P2**：将邑部署面 **D1**（apps/staging/production compose 缺 `jiangyi-gateway` 服务定义，叠加即 502）、**D2**（production `RECOGNIZER_BACKEND:?` 钉在已退役后端）——出自 10-11 将邑审计 §7.1，直接卡「接真硬件」，需现状复核确认是否仍在。
- P3：G2 business 页退款列、G3 押金线上化（可选）、S1 APK dev 默认口令残留。

## ⚠️ CB-035（提现 settle_unlock_hour）落库状态核实（主会话复核更正）

子代理报「本 worktree 全仓零命中」——**零命中属实**（`grep settle_unlock_hour|settleUnlockHour|unlockedThroughDate` 除本文档外代码零命中），但其推论「审计基线落后于 dev」**不成立**，主会话已复核更正：

- `d83c10f2`/`7713289f` **确为 HEAD(9911a2ee) 祖先**（`git merge-base --is-ancestor` 验证通过），即 UI 欠账修复（提现审核 V307 收款方/渠道对账列）已在基线内；本 worktree 就是 dev 最新。
- 真实情况：**CB-035 的 settle_unlock_hour 改动尚未提交**，仍以未提交形态留在主目录工作区（`MerchantWithdrawService.java`、`MerchantWalletAccountDto/OverviewDto.java`、`feature-flags.json`、`sidebar.ts`、`OperatorManageView.vue`、`WalletPage.vue`、4 个提现测试）。`.workbuddy/memory/2026-10-13.md` 的「已推 dev / preflight 45 门禁 0 失败」为**自报未完成态**，按纪律不予采信。
- 影响：Track B-1（提现口径）与 C-admin（提现审核流程）相关结论需在该批改动落库后复核；台账登记为待办。
