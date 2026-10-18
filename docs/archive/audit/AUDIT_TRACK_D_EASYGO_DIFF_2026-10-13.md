# Track D — easygo 业务 diff 增量审计（2026-10-13）

> 范围：D-2 资金池/租赁押金复验、D-3 NOT_FALL vs 低置信 DISPUTED、D-4 识别永不到账超时兜底对拍。
> 不重复 2026-10-01 对照表 20 条与 GAP_SCAN G1–G8。
> 背景：余额-only（WECHAT_PAY_ENABLED=false、CHECKOUT_BALANCE_ONLY=true），识别=将邑端侧。
> 【旧】=`D:\ideaCode\easygo`（只读参照）；【新】=本 worktree。

## 结论速览

- **上线阻塞项：0**。三项均为新系统语义更严或刻意偏离（有文档声明）。
- D-3 判**非上线阻塞**（DC-3 拍板参考：新链资金安全严格优于旧链，最坏情形=订单滞留人工队列）。
- D-4 无偏差、无待拍板：双层兜底（网关 SessionWatchdog 秒级 + trade @Scheduled 10min 状态机）是**净增强**——旧系统对"识别上报永不到账"纸面无终态。
- D-2 三行"待拍板/接真前复查"：**接微信/支付宝前**必须复核（见 §D-2 结论）。

## D-2 资金池 / 租赁押金语义偏差表

| # | 维度 | 旧系统 | 新系统 | 判定 |
|---|------|--------|--------|------|
| 1 | 押金返还触发 | 按月全自动：`yyyyMMdd%100==guaranteeReturnDate` 命中即返一期入可提余额【旧】`MachineGuaranteeCashTask.java:47,67,259` | 无自动返还；退租结算动作驱动（settle 时 refund=balanceCents，人工发起，ops:rental:manage）【新】`DeviceRentalService.java:158-180`；类文档 L29-43 明确声明不采纳按月返还 | **有意设计** |
| 2 | 押金档位/月返还额 | 硬编码档 0/500/1000/2000 元，unit {0,5000,10000} 分【旧】`GuaranteeCashType.java:8-10,23-26` | 实收登记 `device_rental_deposit.balance_cents`，无档位；DEDUCT/RETURN 事件流水 reason 必填【新】`V342__device_rental.sql:24-47`、`DeviceRentalService.java:111-154` | **有意设计** |
| 3 | 到期奖励 200 元 | GUARANTEE_EXPIRE_REWARD 达阈值月发放（CASH_EXPIRE_REWARD_AMOUNT=20000）【旧】`MachineGuaranteeCashTask.java:129-207` | 无到期奖励；押金定位=负债不进资金池台账【新】`V342:6-7` | **有意设计**（需运营话术同步租机方） |
| 4 | 押金扣款方向 | 绑机即实扣 amountCashAvailable（乐观锁×3），解绑退剩余扣安装费 100 元【旧】`GuaranteeCashService.java:49-107,167-248` | 线下收款+人工登记 registerDeposit【新】`DeviceRentalService.java:111-130,134-154`（余额非负 L146-149） | **有意设计**（余额-only 无自动扣款通道） |
| 5 | 租金欠费处置 | 账单仅后台只读，生成/追缴在仓外；旧退押金 MIS 路径有 `YYYYMMdd` 年格式 bug【旧】`EgoOptMachineRentCashGuaranteeRecordServiceImpl.java:117` | 日任务出账 UNPAID→逾期 15 天（可配）自动挂欠租锁柜，结清仅解本前缀锁【新】`DeviceRentalService.java:51-53,316-387` | **有意设计**（锁柜拒卖替代扣款）；旧 L117 仅历史注脚 |
| 6 | 可提余额入账口径 | 日账单收入直接进可提（RT 显示不可提但落账即计），提现千6可覆写【旧】`BillGenerationTask.java:427,431-464`、`OptAccountService.java:191-194` | pending/credited 两态 + 保证金日结固化 solidifyYesterday→solidifyMargin【新】`MerchantSettlementBill.java:45`、`FundBillService.java:409`、`FinanceMarginLockScheduler.java:29` | **接真前复查**：新模型是否等价覆盖旧"当日账单不可提"防资损语义 |
| 7 | 资金分桶模型 | 三桶 cashNoTicket/cashNoAssessment/amountCashAvailable，开票审核驱动流转【旧】`BillGenerationTask.java:466,503,540,577` | 平台侧=渠道账户台账（WECHAT/ALIPAY/BANK × RECHARGE/SWEEP/ADJUST/PAYOUT），开票语义未建桶【新】`V341__platform_fund_pool.sql:16,43` | **接真前复查**：若开票合规要求相同则属功能缺口 |
| 8 | 提现备付校验 | 无资金池概念，提现即打款，失败走释放重提循环 | 软启动：余额≥当日待付净额不足**只告警不拦截**（APPROVED 静默跳过可重试），注释刻意不复刻旧循环【新】`PlatformFundService.java:30-45,96-148`、`V341:5-6,54-56` | **有意设计**（余额-only 正确）；**接通道后须升级硬对账门禁** |
| 9 | 费率模型 | 运营商固定千20、商户提现费千6 可覆写、流量费 3000【旧】`ConstantUtils.java:7,11` | 订单级分成 order_revenue_split 逐单拆分；线长佣金独立【新】`OrderRevenueSplitMapper.java:166-167`、`LineWithdrawPayoutService.java:40` | **有意设计**（抽佣时点前移到成交拆分） |

**小结**：9 行中 6 有意设计、3 接真前复查（#6/#7/#8），新系统内无"疑似缺陷"。

## D-3 NOT_FALL 掉货自动退款（旧）vs 低置信 DISPUTED（新）

### 旧系统自动退款链实况（覆盖面极窄，先纠正常见误设）

| 路径 | 触发 | 关键限制 | 证据【旧】 |
|------|------|----------|-----------|
| A. MQTT 出货失败即时退 | SErr∉{0,6}→NOT_FALL+autoRefundOrder | **L546 `!isTodayOrder` 跨日永不自动退**；余额单退余额；其余"请手工退款" L630 | `MqttMachineService.java:298-313`；`MiniShopReportService.java:530-663,679` |
| B. MNS REFUNDING_ORDER | 消费端 autoRefundOrder；视觉柜 IR_NOT_FALL 仅硬编码单台 M9 `{1514268738825L}` | 通用自动退款 `isOrderNeedRefund` 已整体注释 | `CommonMsgComponent.java:114-123`；`OrderController.java:187-195,236-245`；`MachineOrderService.java:155-158,216-225` |
| C. quartz 30 秒兜底 | refundForWaitingFall 当日、payCategory∈{2,3,17}（**余额单不退**）、限 30 笔 | 出货语义非识别语义 | `RyTask.java:410-441`；`EgoMachineOrderDetailServiceImpl.java:229-258,316-333` |

### 新系统低置信链

低置信→**不扣款转争议**：`SettlementRecognitionService.java:131-143`（DISPUTE_AUTO_OPEN 默认 true）→ escalateToDispute L359-366（releaseIfFrozen→createTicket→抛异常，**绝不出账**）；置信带 `SettlementConfidenceService.java:16-19,37-73`。超时自动免单 `DisputeAutoWaiveScheduler.java:63`（15min 一轮）默认**关**（`DisputeAutoWaiveProperties.java:7,11` fail-closed），仅"识别超时"前缀+未挂单+72h 才 WAIVE，**零资金不变量**；防薅 3 笔/7 天、单轮 50。

### 无人值守夜间差异矩阵（要点）

| 场景 | 旧 | 新 |
|------|----|----|
| 掉货确认当夜 | 渠道单已扣→30s/quartz 自动退（余额单不自动退进人工） | 从未扣（预授权仅冻结），releaseIfFrozen 即等价退款，不进人工 |
| 识别低置信 | 无机制，全人工 | 不扣款→争议队列+SLA；自动免单默认关 |
| 识别永不到账 | 不退款挂账，次晨人工 | 释放预授权→DISPUTED 超时单（见 D-4） |
| 余额挂账 | 跨日永不清算（A′ L546）且无告警 | 冻结随会话终态释放，不存在跨日挂账 |

**判定：不构成上线阻塞**。风险点仅两个（均软性）：
1. DisputeAutoWaive enabled=false 时，小团队夜间会造成争议单积压（用户体感"钱被按住"——实际冻结已释放，仅影响商户侧账实呈现，未裁决不对齐 `alignOrderStatusAfterDisputeResolve:662-683`）。
2. DISPUTE_AUTO_OPEN=false 逃生阀若生产误开，低置信将直接扣款。

**建议进上线 checklist**：①争议 SLA 值班响应 SOP；②DISPUTE_AUTO_OPEN/DISPUTE_AUTO_WAIVE 生产配置值审计。

## D-4 识别上报永不到账 · 超时兜底对拍

新系统双层：

**第 0 层 网关探测**【新】`jiangyi-gateway/.../SessionWatchdog.java:13-25,53,79,94,109,126`（CB-022）：OPEN 15s→postOpenFailed；CLOSE 300s、DOING 600s→recognizeTimeout；MODEL 600s→modelPushTimeout；Redis 锁防重。

**第 1 层 trade 状态机定时清扫**【新】`SessionExpireService.java`（阈值 `SessionExpireProperties.java:13-16`：开门 90s/补货 30min/门开 10min/识别 10min）：

| 起始态 | 阈值 | 终态 | 资金动作 | 证据行 |
|--------|------|------|----------|--------|
| CREATED/OPENING | 90s | CANCELLED+OPEN_TIMEOUT 告警 | releaseIfFrozen 不扣款 | :71-102,382,390 |
| 消费者开门不关 | 10min | DISPUTED+DOOR_OPEN_TOO_LONG CRITICAL 超时单 | 释放预授权不扣款（L438-439 注释：防"取货拖门不关即免单"） | :199-232,441-506 |
| RECOGNIZING/WAITING_UPLOAD/SETTLING（**本场景**） | 10min | DISPUTED+「识别超时，本次暂未扣款」RECOGNITION_TIMEOUT HIGH | releaseIfFrozen(:323)→DISPUTED(:327-329)→createTimeoutTicket(:331，`DisputeService.java:191-204` confidence=0 不扣款) | :235-277,318-345 |
| FAILED 冻结残留 | 1h | 清扫释放 | releaseIfFrozen | :151-194 |
| 补货购物/识别超时 | 30min/10min | COMPLETED+快照 或 CANCELLED | 不涉消费者资金 | :105-139,347-360 |

旧系统对照：ego-automat 内 **@Scheduled 全部已注释**（`MachineStatusTask.java:39-69`、`MachineOfflineTask.java:51`、`MsgSendComponent.java:22-30`），唯一定时资金兜底 refundForWaitingFall 是当日/渠道单/出货语义；**识别不到账纸面无终态**（已扣+未结算，靠次晨人工）。

**结论：新实现净增强，无偏差项、无待拍板项**。多实例幂等由 DistributedLockService/ScheduledTaskRegistry 保证。纸面提醒：SessionWatchdog CLOSE 300s 先于 trade 600s，网关长期宕机时仅靠第 1 层 10min 兜底，仍收敛同一资金终态，可接受。
