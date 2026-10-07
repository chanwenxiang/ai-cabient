# 业务能力三方对比与缺口清单（2026-10-06）

> **对比对象**：**A 方 = 本仓**（`D:\ai-generated code\ai-cabinet`）｜**B 方 = 旧系统**（`D:\ideaCode\easygo`，同公司上一代弹簧柜）｜**C 方 = 行业**（友宝 UBOX 2429.HK / 丰宜科技 / 哈哈零兽 / 拉卡拉分账通等，2026-10-06 WebSearch 取证）
> **方法**：4 个域并行独立审计（资金结算 / 仓储补货 / 设备运维 / 营销三端），每域**必须同时对照三方**；主审对 P0 逐条回源码复核。
> **与既有文档的关系**：`COMPETITOR_BENCHMARK_AND_ROADMAP_2026-09-17.md` 指出三大差距（视觉未落地 / 资金末梢未闭环 / 增长玩法空白）。**本文不是那份的重复** —— 那份是「差距方向」，本文是**逐能力项的 file:line 级取证**，并新增了它完全没覆盖的两个 P0（见 §1）。

---

## 0. 一句话结论

**A 方在「交易主链路 + 仓储批次 + 设备接入骨架」上已明显超越 B 方**，差距不在补功能，而在**三处「结构已建、链路未通」的半成品**与**一处技术自欺**：

| # | 结论 | 性质 |
|---|---|---|
| 1 | 分账款**写入** `settleAfter=T+1`，但**入钱包不设闸门** ⇒ T+1 冻结期形同虚设 | 🔴 P0 资损 |
| 2 | 提现手续费**已扣但不可见**：冻结/扣款走毛额、渠道只发净额，差额天然留存平台；但商户流水只有一条 `-毛额`、平台侧无任何收入汇总 ⇒ **既无法给商户解释，也无法与渠道对账** | 🔴 P0 合规（**非资损**） |
| 3 | 出款侧**完全不对账**（对账只做收入侧） | 🔴 P0 合规 |
| 4 | 端侧视觉 `NcnnYoloDetector` **逻辑倒置**：有模型时返回**空识别**且不报错 | 🔴 P0 技术自欺 |
| 5 | A 方的能力**一半是「默认关的功能开关」而非缺口** —— 7 个开关里 5 个已实现待运营打开 | ⚠️ 决策项，非欠债 |

---

## 1. 🔴 主审复核成立的 P0（4 条，全部逐行读源码）

### P0-1 分账款 T+1 清算前即可提现 —— 冻结期形同虚设

**证据**：`RevenueSplitService.java:112` 写入 `setSettleAfter(LocalDate.now().plusDays(1))`，紧接着 `:127` `creditWalletIfLedgerOnly(split)` **全额入钱包**。

**核实「无人读取」**：全仓 `getSettleAfter()` 的读取点只有 `MerchantFinanceService:389`、`MerchantService:374`、`OpsWorkbenchQueryService:364-365` —— **全部用于展示/查询，没有一处拿它做提现闸门**。

**触发**：商户当天有一笔订单分账 → 立即可提现 → 用户提走 → 随后微信分账失败/退款 ⇒ 平台垫付。
**B 方对照**：`EgoOptBillSettlementMonthly.java:43` **按月结**，天然隔离。
**行业**：持牌分账统一「备付金待结算 → 已结算」两段。
**修法**：新增 `unsettled_cents` 字段 + 提现可用余额 `balance − frozen − unsettled`。**中等成本。**

### P0-2 提现手续费：钱扣了，但**既不可见也不可对账**

> ⚠️ **本条已于 2026-10-06 自我更正**：初版写的是「算出、不扣、不入账」，并判定为「🔴 P0 资损
> + 手续费封顶从未生效」。**该判断错了**，逐行复核后更正如下。保留原判据以便追溯。

**原判据（错在哪）**：`MerchantWithdrawService:413/428/522` 冻结、`:541` 扣款全部用
`getAmountCents()`（毛额），从未减去 `feeCents` ⇒ 推论是「手续费没被扣」。

**实际机制**：申请冻结**毛额** → 打款成功扣**毛额** → 而 `PayoutService` 传给渠道的是
`netPayoutCents = 毛额 − 手续费`。三段串起来，**手续费已经实实在在被扣走了**，
差额天然留存平台。**冻结不扣净额是正确的**（申请时冻结净额会少冻，渠道拒付时无从释放）。

**真正缺的两件事**：

| 缺口 | 后果 |
|---|---|
| 商户流水只有一条 `WITHDRAW_PAID -毛额` | 商户看账单：「申请 100 元，为什么钱少了、手续费却查不到这一笔」 |
| 平台侧无任何手续费收入汇总 | 「今日手续费收入是多少」在任何页面都查不到 ⇒ 无法与渠道流水对账 |

**因此性质从「资损」降级为「合规 / 可解释性」**，但仍需修 —— 资损虽然没发生，
**可解释性与可审计性缺失同样过不了持牌机构的资金流审计**。

**B 方对照**：`EgoOptPositionPayeeInfo` 独立收款方表 + 手续费独立字段，出款与手续费分列。
**修法（已落地）**：冻结/扣减口径**不动**，改为**拆分记账** ——
`WITHDRAW_PAID -净额` + `WITHDRAW_FEE -手续费`（两行相加 == 原毛额，账单总和不变）。

### P0-3 出款侧完全不对账

`ReconciliationService.java:144-151` 只比对「本账 vs 平台账单」，全类 **grep `payout`/`withdraw` 为空**。
**B 方对照**：`EgoOptPositionBillPaymentDetail` + `TransferService.alipayFundTransCommonQuery` 处理 `paymentStatus=4`（转账中）终态补查。
**行业**：持牌机构要求资金流/订单流/发票流三流一致。
**修法**：新增 payout 账单 provider + 查单补偿（与 `PayoutChannel.java:95` 注释承诺的一致）。**中等成本。**

### P0-4 端侧视觉「逻辑倒置」—— 有模型时返回空识别

**证据**（主审亲读 `edge/android-app/.../vision/NcnnYoloDetector.kt` 全文 33 行）：

```kotlin
val useMock: Boolean = !File(modelPath).exists()
val available: Boolean = true          // :11  恒真
fun detect(frame: Bitmap): List<Detection> {
    if (!useMock) return emptyList()   // :24  ← 有真模型时返回空
    return listOf(Detection("SKU-DEMO-001", 0.92f))
}
```

**核实影响面**：`useMock` **只在检测器内部使用**，`CabinetController.kt:33` 的 `useMockDriver` 是**串口驱动**的开关（同名不同义，易混淆），**没有任何保护逻辑**拦截「模型存在」这一路径。

**后果**：当前靠「模型文件不存在」这个隐式开关侥幸走 mock 分支。**一旦运维把权重放进 assets，端侧会静默返回零识别、不报任何错**，表现为「每单都需人工复核」，试点必炸。且类名与 docstring（「NCNN YOLO 推理」）严重不符。

**修法**：① `available` 改为真实条件；② `!useMock` 分支改为「未接入 native ⇒ 抛明确异常」而非返回空；③ 加单测钉住「有模型时不得返回空」。**低成本（现在改），高成本（试点后改）。**

---

## 2. 缺口清单（按域，全 66 项中的关键项）

### 2.1 资金与结算域（14 项）

| 缺口 | A 方现状 | B 方做法 | 严重度 | 成本 |
|---|---|---|---|---|
| **线长提现无收款方模型**（V307 只补了商户侧） | `V307:68` 仅 ALTER `merchant_withdraw_request`；`LineWithdrawService:267` 无收款账户解析 | `EgoOptPositionPayeeInfo` 独立收款方表 | **P0** | 中 |
| **线长打款不走统一通道抽象** | `LineWithdrawPayoutService:27-47` 硬编码微信零钱、恒失败，不用 `PayoutChannelRegistry` | 统一走 `TransferService` | P1 | 中 |
| 缺联行号 `bankCode` + 开户行省市 | `payout_account` 仅 `bank_name`/`bank_branch` 文本；`PayoutCommand` 无 `bankCode` 参数 | `EgoOptPositionPayeeInfo:69` `bank_code`；`TransferService:104` 对公**强制传联行号** | P1 | 小 |
| 无提现实名/KYB 校验 | `IdentityVerifyClient` 仅 C 端注册用；两套提现服务零引用 | 无（合规更弱） | P1 | 小 |
| 缺银行预留手机号（对公二次确认） | `payout_account` 无该列 | `EgoOptPositionPayeeInfo:75` `contact_mobile` | P1 | 小 |
| 开票金额无「未开票冻结」 | `InvoiceService:60-101` 纯工单流，不改余额 | `EgoOptUserAccount:41,45` `cashNoTicketTotal`/`cashNoAssessmentTotal` 两个资金占用科目 | P1 | 中 |
| `site_rent_bill` 无审核环节与付款凭证 | `SiteRentBillService:117` `markPaid` 直接 UNPAID→PAID，无审核人/时间/单据 | `EgoOptPositionBillPayeeMonthly:107/111/115` `feeVerifyName`/`feeVerifyTime`/`feePaymentDetailId` | P1 | 小 |
| 无保证金/押金模型 | `DeviceInfo:90` `depositCents` 仅用于**预授权额度**，无收取/退还/残余台账 | `EgoOptMachineRentCashGuaranteeRecordServiceImpl:86-131` 退至余额/期满奖励/逐笔记录/微信通知 | P1 | 中 |
| 通道费率硬编码 0.6% | `FundBillService:43` `private static final double CHANNEL_FEE_RATE = 0.006` | 各自渠道配置表 | P2 | 极小 |
| 无到账时效/截单配置 | `WithdrawPolicyResolver` 仅 min/max/daily/fee/cap/threshold | `TransferService:86` `withdraw_timeliness: T0` | P2 | 小 |
| 无打款退票/查单终态回查 | 三个 `transfer()` 无一实现查单 | `TransferService.alipayFundTransCommonQuery` 定时补查 | P2 | 中 |
| 银行代付单日限额 | `BankPayoutChannel:53` 恒 `return 0L`，且校验处未调用 | — | P2 | 小 |
| 微信分账 30% 上限未校验 | `RevenueSplitService:103` `merchantShare` 可达 100% | — | P2 | 小 |
| 无分期/按量计费账单 | `site_rent_bill` 仅一次性 | `EgoOptPositionBillPayeeMonthly:91/95` `feeRate`+`feeUnitPrice` | P2 | 中 |

### 2.2 仓储与补货域（13 项，**本域 A 方大幅领先**）

| 缺口 | A 方现状 | B 方做法 | 严重度 | 成本 |
|---|---|---|---|---|
| 货损/盘亏**责任归属**缺失 | `InventoryWriteOff:20-32` 仅 deviceId/skuId/reason/operatorId，**无 merchantId/supplierId/理赔单号** | `EgoOptStockRecordMonthDetail:40-76` 有 optorId+配送员双人 | P1 | 中 |
| 仓库侧盘点差异无原因分类、直接改账 | `WarehouseService:258-286` delta 直接改库存 + 流水，无 reason/审批 | 人工分类 `stockType/remark` | P1 | 中 |
| 跨仓调拨在途无差异/损耗记录 | `WarehouseTransferService:148-167` receive **按行全量入库**，无 LOST/DAMAGED 分支 | 无跨仓调拨实体 | P1 | 中 |
| 盘亏不联动供应商应付 | 应付仅 `ProcurementService:492/391` recordReceive/recordReturn 驱动，**盘亏不入账** | 无 supplier 表 | P1 | 中高 |
| 仓库侧无「核销/报废」（仅设备侧有） | `InventoryOpsService:28` writeOff 仅设备侧 | 无 | P1 | 中 |
| 仓库侧无近效期预警阈值 | 仅 `idx_wh_inv_expiry` 索引，**无预警/看板** | 无批次维度 | P2 | 小 |
| 采购退货无原因分类/残次品处置 | `ProcurementService:347` status 硬编码，notes 自由文本 | 无 | P2 | 小 |
| 供应商无对账单 | `SupplierPayableService:108-137` 仅汇总余额+逾期 | 月度明细+差异数人工核对 | P2 | 中 |
| 仓库月结**不接财务结算** | `WarehouseMonthlyCloseService:34` 注释明写「与结算金额无关」，gap 只展示 | 月度台账人工算赔 | P2 | 高 |

### 2.3 设备运维与识别域（15 项）

| 缺口 | A 方现状 | B 方/C 方 | 严重度 | 成本 |
|---|---|---|---|---|
| **真机不实现 4/5 运维指令** | `MqttDeviceClient.kt:228` 唯一 `if` 分支只认 `OPEN_DOOR`，`REBOOT`/`LOCK`/`UNLOCK`/`SET_TARGET_TEMP` 静默丢弃**不回 ACK** ⇒ `DeviceCommandTracker` 15s 后置 `TIMEOUT` | UBOX 明确支持远程重启/参数调整/固件升级 | **P0** | 中 |
| **设备无自检能力**（本轮新增，见 §8） | 全仓 `selfTest`/`自检`/`diagnos` **0 命中**；设备故障遥测仅 `DOOR_OPEN`/`DOOR_CLOSE`/`OTHER` 三类 | UBOX/行业均有上电自检 + 部件健康上报 | **P0** | 中 |
| **云端视觉生产不可用** | `quectel_recognizer.py:33` `available=False` 硬编码；`main.py:49` secure 环境抛 RuntimeError | 云端通常只做复核 | **P0** | 高（外部 SDK） |
| **APK 硬编码共享凭据 + debug key 签名 + cleartext + OTA 不验签** | `build.gradle.kts:16,32,21`；`signingConfigs` 全仓 0 命中；`AndroidManifest:19` cleartext=true；`OtaChecker.kt:71,204` 仅 SHA-256 | B 方 OTA 同样不验签 | **P0** | 中 |
| 无远程配置下发 | `EdgeRuntimeConfig.kt:7` 仅本地 prefs | B 方 `MachineOperController:112-138` 运营 API 直改 | P1 | 中 |
| 无设备健康评分/预测性维护 | `network_rssi` 有列（`V68:6`）**零写入方** | B 方 `Actions:10` 有 `ACTION_CSQ` 信号强度 | P1 | 中 |
| 耗材（打印纸/电池）零管理 | 全仓无相关字段 | — | P2 | 中 |
| 货道级故障无独立遥测 | `DeviceFaultReportService:37-39` 仅 DOOR_OPEN/DOOR_CLOSE/OTHER | B 方 `Actions:7` `ACTION_MotorError` 带 ChannelIndex | P2 | 低中 |
| 无坏机自助诊断 | 无设备侧自检指令 | — | P2 | 中 |
| 灰度无分批推进语义 | 分桶已真生效（`OtaCdnService:31-41`）但无「第 N 批/暂停/回滚」 | — | P2 | 低 |
| `OFFLINE_AFTER_MINUTES=2` 硬编码 | `DevicePresenceService:26` `private static final` | `HeartBeatHandler:24` 同类 | P2 | 低 |
| Kafka 识别入口绕过 deviceId 校验 | `VisionRecognitionListener:51-69` 零校验（当前因 async 关闭不可利用） | — | P1* | 低 |

### 2.4 营销会员与三端（10 项 + 6 项决策项）

| 缺口 | A 方现状 | 严重度 | 成本 |
|---|---|---|---|
| **广告收益对账/入账** | 组件侧已接腾讯流量主（`device-ad-banner.vue:3` 三源优先级：自有投放 → 腾讯流量主 → 占位）；**但服务端无 eCPM/收入上报**（全仓 grep `ecpm\|adRevenue` 零命中）⇒ 广告能展示、**收入算不出来** | P1 | 中（3-5 人日） |
| **分享/邀请裂变归因与奖励** | `Member:33,35` `inviteCode`/`invitedBy` 是**死字段**（全仓无读写）；`marketing/index.vue:114` 注释自认 | P1 | 中（5-8 人日） |
| 抽奖/互动游戏 | grep `抽奖\|lottery` 全仓 0 命中（B 方 `LuckDrawCtrl:27` 有） | P2 | 高 |
| 券可用范围维度 | `CouponDefinition` 仅金额/门槛（B 方有省/市/区/机器/品牌 8 维） | P2 | 中 |
| 触达渠道仅站内信 | `NotificationProperties:5` 默认仅站内信 | P2 | 低 |
| 会员等级权益仅文案 | 无自动生效 | P2 | 中 |
| 营销 ROI 看板单维度 | `MarketingRoiService:48` 仅 `list(days)` | P2 | 低 |

**⚠️ 6 项「决策项」不是缺口**（A 方**已实现**，默认关是纪律）：
`orderSearchEnabled`(`SystemConfigService:511`) / `couponEntryEnabled`(`:513`) / `productDetailEnabled`(`:515`) / `chartsEnabled`(`:537`) / `payChannelSelectEnabled`(`:518`) / `adBannerEnabled`+`wxAdEnabled`(`:521,524`)。
**这 6 个开关是否打开是运营决策**，不是开发欠债 —— 需要的是「开不开、为什么」的判断，不是「补实现」。

### 2.5 广告变现路线定案（2026-10-07 用户拍板）

**用户明确**：我们**不出广告主（不自购流量）**，只做**腾讯流量主的广告位**，
从腾讯的 eCPM 结算里赚钱。

**这个定案把上一版「自有广告主 vs 流量主」的二选一消掉了**，
并连带影响两件事：

| 影响面 | 结论 |
|---|---|
| `adBannerEnabled` / `wxAdEnabled` 开关 | 从「待决策」变成**「流量主路线要上线就得开」** —— 但仍需运营确认投放策略与广告单元 ID |
| 2.4 第 1 项「广告收益对账/入账」 | **从 P1 升为路线阻塞项**。展示链路已通（组件接流量主），但**服务端零 eCPM 上报** ⇒ 现在能展示、**算不出收入**。走流量主路线时这笔钱是唯一收入来源，却对不上账 |

🔴 **因此待做的不只是「打开开关」**，而是补齐收入侧：
① 小程序侧上报 eCPM/曝光/点击（自有素材已有 `POST /marketing/ads/{id}/events`，
**腾讯流量主那条路径没有**）；② 服务端收入台账与对账（谁投的、产生多少、怎么分）。
否则「有流量主展示但账上没钱」= 收入凭空消失。

⚠️ 另外注意命名混淆：`device-ad-banner.vue` 现在是**推广位**（自有素材 + 腾讯广告 + 占位
三源择一），不只广告。查代码时别把它当纯广告位。

---

## 3. 意外发现（文档说缺、实际已有 / A 方优于 B 方）

1. **A 方仓库侧 FEFO 已实现，是本次最大反转**。`WarehouseService:1067` 按 `expiry_date` 升序 + 分布式锁逐批占用、`:1096` 过期批次跳过、`:1101` 扣 `sumAllocatedQty` 防超发 —— 比设备侧 `InventoryLotService:160` 更严谨（有 `ForUpdate` 行锁）。B 方完全没有。

2. **A 方优惠券能力已反超 B 方**。B 方 `CouponService` 只有 5 个方法（发出去就管不了）；A 方 24 个公开方法，含 `selectBestCoupon`（最优券自动择优）、`recalcOrRestoreAfterPartialRefund`（部分退款后重算）、`expireOverdueCoupons`（全生命周期状态机）。

3. **A 方资金域「注释诚实度」显著高于 B 方**。`MerchantWithdrawPayoutService.modeInfo():137-158` 主动向运营暴露「当前是记账打款/通道就绪状态/密钥是否配置」，并用运营语言写清「标记成功 ≠ 钱已到账」；`LineWithdrawPayoutService:75` 硬编码 `transferApiReady: false`。B 方金额用 `Integer` 分 + `amount * 100` 手工换算（`EgoOptMachineRentCashGuaranteeRecordServiceImpl:101`），无幂等无乐观锁，重入即资损。

4. **B 方在信号质量遥测上比 A 方强**：`Actions:10` 有 `ACTION_CSQ`（4G 信号强度）并在 `MessageHandler:52` 处理。A 方 `network_rssi` 列已建却零写入方 —— 属「**迁移时丢了旧能力**」而非从未实现，补起来极低成本。

5. **B 方 `MotorError` 处理被注释掉**（`MessageHandler:66-67` 整段 logger 注释）—— 上一代踩过货道电机告警噪声过大的坑才注释。**A 方补货道级遥测时应先设计告警收敛/去抖**，否则重蹈覆辙。

6. **A 方提现收款方模型质量高于 B 方**。B 方 `EgoOptPositionPayeeInfo:42` 的 `identity` 是**明文存银行卡号**，无掩码、无加密、无跨主体鉴权 —— 是合规负债。

7. **`ops.log_retention.points_months` 的废弃说明是极好的架构自律证据**（`SystemConfigService:912-913`）：配置项曾承诺「积分日志清理」，但账本要求禁止 DELETE，于是**主动把历史库描述刷成「已废弃·请勿使用」** —— 用代码纠正误导性描述而非留着骗人。

---

## 4. 建议修复顺序

### 第一批：低成本高杠杆（本周）

| # | 项 | 成本 |
|---|---|---|
| 1 | **P0-4** `NcnnYoloDetector` 逻辑倒置 + `available` 恒真（现在改成本极低，试点后改成本极高） | 极小 |
| 2 | ~~**P0-2** 提现冻结改净额~~ → **已落地 V308**：冻结口径不动，改为拆分记账（`WITHDRAW_PAID` + `WITHDRAW_FEE`） | ✅ 完成 |
| 3 | ~~补**联行号 `bankCode`** + 开户行省市~~ → **已落地 V309**：`payout_account` 补 2 列并贯通到 `PayoutCommand` | ✅ 完成 |
| 4 | ~~`channelFeeRate` 改可配~~ → **已落地 V309**：改 `fund.channel_fee_bps` 运营台配置（整数 bps + 上界钳制） | ✅ 完成 |
| 5 | ~~`site_rent_bill` 补审核人/审核时间/付款凭证三字段~~ → **已落地 V309**：补 `paid_by`/`paid_voucher_no`/`paid_remark` 并写进审计日志 | ✅ 完成 |

### 第二批：需设计决策（接真前）

| # | 项 | 关键决策点 |
|---|---|---|
| 6 | ~~**P0-1** 分账 T+1 冻结期闸门~~ → **已落地 V308**（决定：不做 `unsettled_cents` 新字段，改**查询式扣减**，理由见下） | ✅ 完成 |
| 7 | ~~**P0-3** 出款侧对账~~ → **部分落地 V308**（本地三方自洽口径 + `GET /payout-reconciliation`）；**渠道账单比对仍缺**，等通道接通 | ⚠️ 部分 |
| 8 | ~~**P0** 线长提现补收款方模型 + 接通道抽象~~ → **已落地 V308**（与商户侧同构） | ✅ 完成 |
| 9 | **P0** APK 凭据 → 逐设备 + `signingConfigs` + OTA 验签 + 关 cleartext | 证书体系与 provisioning 流程 |
| 10 | **P0** 真机补 4/5 运维指令（远程救砖能力） | 与硬件方确认指令语义（`REBOOT` 是否落盘、`LOCK` 锁货道还是整柜） |
| 10a | **P0** 设备自检 + `SELF_TEST` 指令（**用户 2026-10-06 判定需要**，详见 §8） | **无阻塞，可立即做**；是运维指令的前置判断依据 |
| 11 | 广告收益对账入账 | 是否引入自有广告主（放弃流量主路线会限制 ARPU 上限） |

### 第三批：运营决策（不是开发任务）

- **6 个功能开关是否打开**（订单搜索/券包首页/商品详情/经营图表/支付自选/广告位）—— 需要产品判断，不是补代码
- 分享裂变是否要做（`inviteCode` 死字段已留位）

---

## 5. 核实方式与未核实项

- **主审逐行复核**：P0-1（`RevenueSplitService:112/127` + 全仓 `getSettleAfter` 读取点枚举）、P0-2（`MerchantWithdrawService:413/428/522/541` 冻结与扣减口径 + `FundBillService:230-234` 科目枚举）、P0-4（`NcnnYoloDetector.kt` 全文 33 行 + 调用方 grep）、V307 迁移（PG 实跑 + 唯一索引负向验证）
- **代理分区结论**：2.1–2.4 表格中未标「主审复核」的项，均由 4 个独立审计 agent 提供 `文件:行号` 证据；主审抽样发现并修正了一处**误读**（agent 把新旧系统的 `PAYING 超时转人工` 条目混为一谈，已在文中分开标注）
- **未核实项（诚实标注）**：
  - 竞品 C 方数据来自公开资料与 WebSearch，**非对方内部系统**；友宝/丰宜的分成比例与到账时效可能随时间变化
  - 旧系统仅抽查了与本项目重叠的域（提现/账单/库存/设备/优惠券），**未做旧系统全域清点**
  - `payout_account` 表已在本地 PG 实跑，但**未在生产环境验证过 Flyway 迁移时序**

---

## 6. V308 落地记录（2026-10-06晚）

按用户三条指示执行：① 保留 SKU 建档/争议推荐两条 DeepSeek 建议接口；② 微信限额按
**「单笔 + 总额」**结构化（不是单一 200）；③ 继续补提现断链。

### 6.1 保留（不做的事也要留痕）

`/api/v2/vision/suggest-class`（SKU 建档候选类目）与 `/api/v2/vision/dispute-suggest`
（争议工单推荐 SKU）**经核实是活链路**，不是 YOLO 残留 —— admin 前端在用，且结果需人工
确认才生效。已在 `deepseek_recognizer.py` 模块 docstring 与 `main.py` 两处端点上方写明
「勿当残留删除」，避免下一轮清理再次误删。云端主识别仍是端侧（将邑）上报。

### 6.2 微信限额：三维度而非一个数

核实来源：微信支付官方《设置转账额度》`pay.weixin.qq.com/doc/v3/merchant/4013747667`
（**商户号主体非个体户**一栏）：

| 维度 | 默认 | 可调区间 | 本项目取值 |
|---|---|---|---|
| 单笔限额 | ¥200 | 0.1–200 | `20_000` 分 |
| 单用户单日（单商户号→同一用户） | ¥2000 | 0.1–2000 | `200_000` 分 |
| 单日总额（单商户号，**全平台共享池**） | ¥5 万 | 0.1–5万 | `5_000_000` 分 |
| 单月 | 3000 万 | 不可改 | **不入模型**（改不了的约束不写成可配置项） |

设计要点：

- **新 `PayoutChannelLimits` record**（`singleCents` / `perPayeeDailyCents` / `dailyTotalCents`，
  0 = 不限），由 `PayoutChannel.channelLimits()` 暴露。
- **渠道限额与运营限额取更严者**（`PayoutChannelLimits.effective`），**只允许收紧**——
  运营填 0 表示「不限制」**不能**把渠道额度放开。理由：让运营把单笔配成 ¥10000，
  结果是渠道全拒 ⇒ 商户看到「申请成功但一直不到账」，比当场提示难处理得多。
- 微信按**非个体户**口径；个体户更严（单用户单日 ¥200 / 单日 ¥5000），
  接入个体户收款时**必须改常量**，运营限额压不下来。
- 支付宝**刻意不填数字**（`UNLIMITED`）：额度随签约产品浮动，未核实官方口径前写死常量
  等于「用猜测拦真实资金」。银行通道取协议约定的日上限，未签协议时为 0（不限）。
- 申请时三个维度都校验：单笔、单收款人当日累计、**单通道全平台当日累计**。
  第三个维度是**共享池**，按商户各自统计会严重高估可用额度。

### 6.3 手续费：拆分记账（P0-2 落地）

`MerchantWalletService.consumeFrozenSplit(net, fee)`：**余额与冻结合计只扣一次
`net + fee`**（与拆分前扣毛额完全等价），流水拆两行 `WITHDRAW_PAID -net` +
`WITHDRAW_FEE -fee`。`fee = 0` 时只记一行，行为与接入前完全一致。
平台侧手续费收入（当日/近 30 日）已接入「打款模式」面板。

### 6.4 T+1 闸门：决定不做新字段（P0-1 落地）

**决策：不加 `unsettled_cents` 字段，改「查询式扣减」**——
提现校验时额外扣掉「已入钱包但 `settleAfter > 今日`」的金额
（`OrderRevenueSplitMapper.sumWalletCreditedButNotYetWithdrawable`）。

理由：

1. 改入账路径要动分账主链路（含幂等、冲正、部分退款重算），风险面远大于收益；
2. `frozen_cents` 已被提现流程独占占用，再塞一种语义会让「提现冻结」与「待结算冻结」
   互相污染，出问题时无法区分谁该解冻；
3. 代价是每次提现多一次聚合查询 —— 提现是人工触发的低频操作，可接受
   （与 `WithdrawPolicyResolver` 每次读 `SystemConfig` 同理，都不缓存）。

**状态口径必须与入账口径一致**：只算 `LEDGER_ONLY` / `SETTLED`。
加 `ACCRUED` 会把「走微信分账、钱直接到商户」的钱也算进冻结 —— 那笔钱根本不在我们钱包里。

### 6.5 出款侧对账：本地自洽（P0-3 部分落地）

`PayoutReconciliationService` 核对**三方自洽**：
`Σ(单.amount) − Σ(单.fee) == Σ|WITHDRAW_PAID|` 且 `Σ(单.fee) == Σ|WITHDRAW_FEE|`。
按 `paidAt` 分窗（不是 `createdAt` —— 申请与打款跨天是常态）。按通道分组，固定顺序展示。
端点 `GET /api/v2/ops/admin/merchant-withdraws/payout-reconciliation`（仅 `ops:finance:view`）。

🔴 **返回里 `scope` 恒为 `LOCAL_SELF_CONSISTENT_ONLY`**：三通道 `isReady()` 恒 false，
**拉不到渠道账单**，所以这**不是**与微信/支付宝对平，只是「本地账没有自相矛盾」。
「渠道实际出了多少钱」要等通道接通后补账单拉取 + 查单补偿。

### 6.6 测试与负向对照

新增 4 个测试类共 **42 个用例**全绿，且每处校验都做了**注入错误 → 必须变红 → 还原变绿**：

| 测试类 | 用例 | 负向对照结果 |
|---|---|---|
| `MerchantWithdrawChannelLimitTest` | 13 | 短路 `validateChannelLimits` ⇒ 4 failed + 2 UnnecessaryStubbing |
| `MerchantWalletFeeSplitTest` | 8 | —（断言含两行相加 == 毛额的不变量） |
| `MerchantWithdrawSettleGateTest` | 6 | 令 `pendingSettle` 恒 0 ⇒ 3 failed + 1 error |
| `PayoutReconciliationServiceTest` | 7 | —（含时间窗必须有上界、scope 诚实性断言） |

### 6.7 本轮未做（明确留待后续）

- **渠道账单对账**与**查单补偿**待通道接通（三通道 `isReady()` 恒 false，拉不到对方流水）；
- 微信商户号若已提额（单笔 ¥20000），代码里的 ¥200 会**提前拒绝本可出款的大额**，
  需相应上调 `WeChatPayoutChannel.channelLimits()` 常量。

---

## 7. V308 续：支付宝核实 + 线长侧同构（2026-10-06 晚）

### 7.1 支付宝官方限额（核实后落地，不再是 UNLIMITED）

核实来源：支付宝开放平台《商家转账》`opendocs.alipay.com/open/009zdp` §5.2
+ 官方错误码说明（`EXCEED_LIMIT_PERSONAL_SM_AMOUNT` / `EXCEED_LIMIT_DM_MAX_AMOUNT`）。

| 维度 | 官方口径 | 代码取值 |
|---|---|---|
| 单笔（转个人支付宝账户） | ¥5 万 | `5_000_000` 分 |
| 单笔（转企业支付宝账户） | ¥10 万 | 本通道是对私，取 5 万 |
| 单收款人单日 | **该维度不存在**（支付宝按**付款方商户**计） | `0`（不设） |
| 单日总额 | 默认额度 | `100_000_000` 分（¥100 万） |

⚠️ **官方文档自身不一致**（这点必须记录，不能装作没有）：
`docs.alipay.com/support/01rgfi` 与错误码页写**日 100 万 / 月 300 万**，
另一 FAQ 页（`opensupport...65b9c62c13258407a0b997b4prod`）写**日 200 万 / 月 3100 万**。
两边都注明「支付宝会根据实际转账资金情况进行调整，具体以实际支持为准」——
**官方自己都不保证**。故**取更严的 100 万**：

- 少拦的后果：渠道拒付 → 提现落FAILED（商户能理解，可重试）；
- 多拦的后果：本可出款的钱不让提（运营背投诉）。

**「单收款人单日」刻意不设**：填一个不存在的维度会让运营误以为有这道闸门。

### 7.2 线长侧提现与商户侧同构（V308 迁移 + 改造）

**新增 `V308__line_withdraw_payee_snapshot.sql`**（本地 PG 已实跑，幂等键唯一约束已负向验证）：
`line_withdraw_request` 补 9 列，与 `merchant_withdraw_request`（V307）**完全对齐** ——
收款方快照 5 列 + `payout_account_id` + `idem_key` + `channel_order_no` / `channel_batch_no`。
种子给有余额的线长建**默认对私微信**账户。

**为什么线长默认「对私微信」而商户默认「对公银行」**：这不是配置偏好，是**业务事实**——
线长的钱来自佣金分成、主体是**自然人**；商户是**法人**。硬套会出现「给自然人打对公代付」
这种渠道侧必拒的组合。

**代码改造**（`LineWithdrawPayoutService` 从 96 行硬编码 → 走 `PayoutChannelRegistry`）：

| 项 | 改造前 | 改造后 |
|---|---|---|
| 渠道来源 | `WithdrawPayoutPolicy.channelFor(mock)` 硬编码 | 按**收款账户的 channel 字段**分派 |
| 收款方 | 无快照，出款时读 `manager.getWxOpenid()` | 申请时快照 + 出款时**解密密文** |
| 幂等键 | **无** | `LW:<requestId>:<随机>`，唯一索引兜底 |
| 手续费记账 | 单条 `-毛额` | 拆分 `WITHDRAW_PAID -净额` + `WITHDRAW_FEE -手续费` |
| 渠道限额校验 | **无** | 单笔 / 单收款人单日 / 单通道当日总额 |
| 打款模式面板 | 散落字面量 | 从统一注册表取 `channelReadiness` |

**「单通道当日总额」是商户+ 线长共享池**：微信那个 5 万/日限的是**整个商户号**，
不是每主体各一份。故聚合收口到 `PayoutAccountService.sumPaidAmountByChannelSince`，
**两侧都必须走它** —— 各查各的等于把池子算两遍，限额形同虚设。
同理「单收款人单日」按 `payout_account_id` 聚合（两侧账户可能指向同一人）。

### 7.3 顺手补的一个隐性缺口

`LineWithdrawProperties` **少了 `maxAmountCents` 字段**：V307 补了 SystemConfig 键
`LINE_WITHDRAW_MAX_CENTS` 与 `WithdrawPolicyResolver.lineMaxAmountCents()` 读取，
却没给 record 加字段 ⇒ `pick(key, 0L, 0L)` 永远取兜底 0，**「运营台不配就永不限额」**。
商户侧同期有这个字段，两侧不对称。已补字段 + yml `max-amount-cents` +
修 `lineMaxAmountCents()` 误传 `0L` 的问题。

### 7.4 出款对账纳入线长侧

`PayoutReconciliationService` 现在**同时**统计商户单与线长单、两侧钱包流水。
只对一侧等于「少算一半」，差异会永远显示为不平。
新增回归用例 `lineWithdrawIncludedInRecon`（断言线长的 5000 分必须计入总额）。

---

## 8. 设备自检与运维指令（2026-10-06 20:15 取证，用户判定「应该需要」）

### 8.1 结论：设备自检能力**为零**，不是「做得少」

全仓搜索 `selfTest` / `self_test` / `SELF_TEST` / `自检` / `diagnos`，
在 `edge/android-app/app/src/main/` 下**零命中**。

⚠️ 容易误判的两个干扰项 —— 它们是**服务端**自检，与柜机无关：

| 类 | 位置 | 实际作用 |
|---|---|---|
| `SchedulingPoolCapacitySelfCheck` | `config/` | 定时任务并发 × 连接池容量，启动期一次 |
| `XxlJobWiringSelfCheck` | `config/` | XXL-JOB 执行器接线，启动期一次 |

设备侧现有的全部健康信息只有 `DeviceStatusHub.status`（`status/DeviceStatusHub.kt:9-15`）：

```
mqttConnected / doorState / activeSessionId / lastEvent / lastError
```

**全是运行态事件流，没有一项是「部件健康」**。设备故障遥测
（`DeviceFaultReportService:34-36`）也只有 `DOOR_OPEN` / `DOOR_CLOSE` / `OTHER` 三类，
没有货道级（`channelIndex`）故障 —— B 方 `Actions:7` 的 `ACTION_MotorError` 是带通道号的。

### 8.2 运维指令断链（P0，与自检同源）

| 层 | 现状 |
|---|---|
| `DeviceInternalController:49` | **会**校验并下发 `LOCK`/`UNLOCK`/`REBOOT`；另有独立 `setTargetTemp` 端点（`:40`） |
| `MqttDeviceClient.kt:228` | **只认 `OPEN_DOOR`**，其余类型静默丢弃、**不回 ACK** |

⚠️ **更正本节此前的「永久等待」说法**（说过头了）：`DeviceCommandTracker` 有
`ACK_TIMEOUT_MS = 15_000`（`:37`），`expireCommands()`（`:186`）每 5s 扫一次
（Redis 版走 `ZRANGEBYSCORE`，避免全量 SCAN），超时置 `TIMEOUT` 并记指标。
所以真实症状是「**15 秒后报超时**」，**不是挂死**。但危害不变：
运维点「远程重启」→ 云端返回 `commandId` 像是成功 → 设备**实际没动** →
15 秒后才知失败。**设备死机时唯一的救命通道就是 `REBOOT`，而它现在不通。**

### 8.3 为什么「设备检测」与「运维指令」必须一起做

两者是同一件事的两半，缺一不可：

| 只有自检 | 只有运维指令 |
|---|---|
| 知道「哪台柜机、哪个部件坏了」 | 知道「但修不了」 |
| 运营只能派人去现场 | 运营能远程重启/锁门 |
| —— | **但不知道该重启什么** ⇒ 重启是盲操作，可能把正常柜机也重启了 |

⇒ 建议**同批做**，自检先落地（提供判断依据），运维指令紧随（提供处置手段）。

### 8.4 建议范围（最小可用，不含硬件私有协议）

**阶段一：设备自检（可独立做，不依赖硬件方）**

1. 上电/每日定时自检，回传结构化结果而非 `lastEvent` 文本：
   `network_rssi`（**字段已存在但零写入方**，`V68:6`）、`doorSensorOk`、
   `cameraOk`、`storageFree`、主板温度、`appUptimeSec`；
2. 新增设备指令 `SELF_TEST`（云端可远程触发），与 `OPEN_DOOR` 同机制；
3. 复用现有 `payout`/`telemetry` 上报通道，**不新增协议**。

**阶段二：运维指令（需硬件方确认语义）**

`REBOOT` / `LOCK` / `UNLOCK` / `SET_TARGET_TEMP` 四个分支 + 统一 `publishAck`。
**必须先与硬件方确认**：

- `REBOOT` 是否需要先落盘（未落盘的重启会丢状态）；
- `LOCK` 是锁**货道**还是**整柜**（两者风险完全不同）；
- `SET_TARGET_TEMP` 的取值范围与回读校验。

⚠️ **建议先做阶段一**：它不需要硬件方确认，且是阶段二的前置判断依据。
阶段二的骨架 + `publishAck` 可以先合（让指令不再静默丢弃，至少 15 秒后能给出明确失败），
具体动作待硬件确认后再填。

### 8.5 成本

| 项 | 成本 | 阻塞 |
|---|---|---|
| 阶段一：自检（含 `SELF_TEST` 指令） | 中（2-3 人日） | 无，可立即做 |
| 阶段二：4 个运维指令骨架 + ACK | 小（0.5-1 人日） | 无（语义待补） |
| 阶段二：真实动作实现 | 中 | **硬件方确认语义** |
| 货道级故障遥测（带 `channelIndex`） | 中 | 需硬件协议 |

---

## 9. V309 落地：三处结构性缺失（2026-10-06 23:40）

第一批的三项「零成本小项」已全部落地。共同特征：**能力/字段缺失会让他人无法复核或无法出款**，
不是「体验不好」。

### 9.1 收款账户补联行号与开户行省市

| 层 | V309 前后 |
|---|---|
| `payout_account` | 只有 `bank_name`/`bank_branch` 文本 → 补 `bank_code`（联行号）+ `bank_province_city` |
| `PayoutCommand` | 无联行号参数 → 新增 2 个字段 |
| `ResolvedPayee` | 不携带 → 补2 个字段 |
| 两侧 `*PayoutService` | 传 `bankName, bankBranch, taxNo` → 补传 `bankCode, bankProvinceCity` |

**为什么必要**：银行代付只有「户名+账号+开户行」三要素时，部分银行**无法自动路由**，
打款被退回且失败原因常只写「收款行不匹配」—— 排查成本高。竞品 `EgoOptPositionPayeeInfo:69`
与 `TransferService:104` 都强制传联行号。

⚠️ **刻意不设为必填**：强制会在未签约阶段把所有对公打款拦掉，而那时还不知道对方要哪几要素。
先可选，接渠道时按实际要求再收紧。

### 9.2 通道费率改运营台可配

| | V309 前| V309 后 |
|---|---|---|
| 费率 | `private static final double CHANNEL_FEE_RATE = 0.006` | `fund.channel_fee_bps`（默认 60） |
| 单位 | double 比例 | **整数万分比** |
| 误填防护 | 无 | 上界 1000bps（10%）钳制，超界/负值/非数字**一律回落默认** |

**为什么从 double 改成 bps**：运营手填 `0.006` 极易写成 `0.06`（放大 10 倍）或 `.6`（放大 100 倍），
而这个数字**直接乘在商户结算金额上** —— 填错一行会让平台侧通道费虚高十倍并可能亏穿。
整数 bps + 钳制后最坏情况也被限在 10% 内。

⚠️ **口径未变**：这仍是**按实付金额的估算展示值**，不是渠道实际结算费率，
真实费率以渠道账单为准。做成可配是为了让运营能按**实际签约费率校正展示值**。

**容错取向**：配置缺失/非法时**回落默认而不是抛异常** —— 资金看板因为一个手填错的值
整体打不开，比费率估错更糟。

### 9.3 场地租金账单补付款留痕

| | V309 前 | V309 后 |
|---|---|---|
| 标记已付 | 只写 `status` + `paid_at` | 追加 `paid_by` / `paid_voucher_no` / `paid_remark` |
| 审计日志 | 不含凭证号 | detail 含凭证号 |
| 接口 | `POST .../pay` 无请求体 | 请求体**可选**（`MarkSiteRentBillPaidRequest`） |

**为什么必要**：场地租金是**对外付款**。原先运营点一下就成了「已付」——
**谁付的、凭什么付的、凭证在哪，系统里一概没有**。财务审计里这等于「这笔钱说不清」。

🔴 **留痕必须同时落业务表与审计日志**：只落业务表，将来无法自证凭证号是
「付款时填的」还是「事后被人补上的」。审计日志的时序是唯一证据。

🔴 **幂等不能被覆盖**：已付账单重复调用时**必须保留原凭证号**。
若新调用传 null 就覆盖，等于「后来的运营无意抹掉了付款凭证」且无法恢复 ——
已写成回归用例 `markPaid_alreadyPaid_isIdempotentAndKeepsOldVoucher`。

### 9.4 顺带修掉的 5 个 Kotlin 编译错误（CI edge-android 抓出）

上一批设备改动提交时**本机跑不了 Android 编译**（缺 SDK），只能靠 CI 兜。
CI 报出 5 处编译错误：

| 位置 | 错误 | 根因 |
|---|---|---|
| `MqttDeviceClient:30` | `Unresolved reference: publishAck` | 构造期lambda 里调成员函数（此时 `this` 未初始化完） |
| `OpsCommandExecutor:106-107` | `Too many arguments for reboot` | `compileSdk 34` 只剩 `reboot(String?)`，旧 3 参是隐藏 API |
| `OpsCommandExecutor:177` | `File(...)` 无匹配构造 | **`filesDir` 本身已是 File**，多包了一层 |
| `OpsCommandExecutor:219` | `String?` 但需 `String` | `setError` 无「清空」重载，不该塞空串 |

**第一处的修法值得说明**：原意是「未装配执行器也要回失败 ACK」，
但构造期 lambda 里不能调成员函数。改为 `onOpsCommand: ((OpsCommand) -> Unit)? = null`，
把「未装配」的失败 ACK 移到 `handleOpsCommand` 里兜 ——
**行为不变，但挪到了this 已就绪的时机**。

### 9.5 Android 本地验证配方（重要：以后改 edge 必须本地跑）

```bash
docker run --rm -v "D:/ai-generated code/ai-cabinet:/ws" \
  -v aicabinet-android-sdk:/sdk -v aicabinet-gradle-home:/gradle-home \
  node:24.18.0 sh -c "cd /ws && bash .tmp/android-build.sh"
```

**踩坑记录**：

1. 🔴 **apt 装 JDK 会 502**（Debian 镜像在代理下不稳）⇒ 改**直下 Temurin tarball**
   `github.com/adoptium/temurin17-binaries/.../OpenJDK17U-jdk_x64_linux_hotspot_17.0.20_8.tar.gz`。
2. 🔴 **SDK 必须挂 docker 卷**（`aicabinet-android-sdk`），装在容器可写层会随容器销毁 ——
   本次就是这样白装了一次。
3. 🔴 **`docker run -v ... bash /ws/xxx.sh` 会被 Git Bash 转换路径**⇒ 改用
   `sh -c "cd /ws && bash .tmp/xxx.sh"`。
4. 🔴 **脚本里的 `$PATH`/`$JAVA_HOME` 会被宿主 Git Bash 展开**（注入 Windows 路径导致
   `syntax error near unexpected token '('`）⇒ **必须写成脚本文件**，不要用 `bash -c "..."` 内联。
5. Gradle 用 **8.9**（与 CI `gradle-version` 一致），缓存在 `aicabinet-gradle-home` 卷。

**Gradle 与 Android SDK 已在卷里就绪**（`platforms;android-34` + `build-tools;34.0.0`），
下次改 edge 代码可直接跑上面这条命令，**不必再等 CI 才发现编译错误**。

---

## 10. 弹簧机改开门柜：代码边界（2026-10-07 用户澄清后取证）

**用户澄清的需求**：采购**将邑中间件**做端侧识别，把**现有弹簧机改造成开门柜**。

### 关键取证结论：弹簧机控制代码**不在我们仓库里**
`edge/android-app/.../hal/` 只有三个文件：`ILockDriver` / `ChzhLockDriver` / `MockLockDriver`，
串口协议**只有一条命令**：

```
ChzhLockDriver.kt:94  val UNLOCK_CMD: ByteArray = "L1@200\r\n".toByteArray()
```

⇒ 我们从一开始就**只控制「开/关门」，没有电机/货道/弹簧/硬币/找零的控制代码**。
弹簧机改成开门柜的机械与电气改造（换锁/改结构/接门磁）属于**硬件方与厂商**范围，
不是我们的代码工作量。

### 因此三个问题的答案

**① 「需要之前的安卓代码吗」—— 指的是弹簧机代码，答案是：不需要，因为它不在我们这儿**

我们能给的、必须给的是这三类（都不是弹簧机控制代码）：

| 给什么 | 为什么必须给 | 规模 |
|---|---|---|
| **串口协议现状**（`L1@200` + `DOOR=C/0/CLOSED` 回包解析） | 这是唯一「驯服过这批柜机」的记录。将邑中间件若要接管门控，必须知道现有协议长什么样 | `hal/chzh` 258 行 |
| **云端协议事实来源**（`MqttTopics` + `MqttDeviceClient`） | EMQX 要求 `clientId == deviceId`、心跳 30s、ACK 必须回 —— 这些是对方接我们云时必须遵守的 | `mqtt` 560 行 |
| **云端上报端点**（`POST /internal/v1/vision/edge-results`） | 端点已完整，注释里就写着「第三方按文档接入即可直连」 | 已就绪 |

**不需要给的**：`service` / `ota` / `upload` / `video`（应用层编排，与硬件无关）。

**② `vision/` 已删除（今天 10:00）** —— 231 行零调用方 + 逻辑倒置
（`detect()` 有真模型反而返回空）。**这恰好说明「原来的端侧识别本来就是没接的」**，
不存在「改了会破坏将邑对接」的风险。保留的是解耦后的 `SkuDeltaCalculator`。

### 采购前必须问将邑的三个问题（已写进代码注释与本节）

1. **中间件部署在哪**：原 Android 主板上（给 aar/SDK）还是独立边缘盒（给 HTTP）？
   → 决定我们是否还需要动Android 层。
2. **是否给「开门帧/关门帧」两帧识别**：很多厂商**只给最终 SKU 列表**。
   `SkuDeltaCalculator` 依赖两帧差分算增减（关门比开门多了什么 = 买了什么）；
   只给最终列表 ⇒ 要么让他们直接给增量，要么改成「开门识别 + 结算时识别」做差。
3. **是否有货道级故障码**（哪个货道电机坏了）：行业里 UBOX 这类都有，
   缺了我们要自己接串口。

### 改造后的三条硬约束（写代码时必须遵守）
- **门状态判错比识别错更致命**：早关会把还在购物的用户锁在流程外，晚关则让会话一直挂着。
  `ChzhLockDriver.doorStateFromFeedback` 抽成纯函数就是为了可测（`ChzhLockDriverFeedbackTest`）。
- **不能为「检测电机」发开门命令**：那会在用户购物过程中开柜门（破坏性探测）。
  这就是 `SELF_TEST` 里货道电机项**宁可缺项也不填 0** 的原因。
- **`SET_TARGET_TEMP` 目前只落盘不控温**：真实温控依赖压缩机/继电器，未接入。

---

## 11. 缺口清单真实进度（2026-10-07 逐条核对，纠正 §2 标题的误导）

### 🔴 先纠正文档自己的一个问题
§2 标题写「全 66 项中的**关键项**」，但表里只有
**14 + 9 + 12 + 7 = 42 项**（加 6 项决策项= 48）。
**66 是四个域的总数估计，不是逐条列出的数** —— 标题让读者以为「文档里有 66 行可查」，
实际没有。补这份对照表就是为了让「还剩多少」有个可核查的答案。

### 2.1 资金与结算域（14 项）

| # | 缺口 | 状态 | 依据 |
|---|---|---|---|
| 1 | 线长提现无收款方模型 | ✅ **V308 已完成** | `LineWithdrawRequestDto` +7 快照字段；`LineWithdrawPayeeTest` 13 例 |
| 2 | 线长打款不走统一通道抽象 | ✅ **V308 已完成** | `LineWithdrawPayoutService` 改走 `PayoutChannelRegistry` |
| 3 | 缺联行号 + 开户行省市 | ✅ **V309 已完成** | `payout_account` +2 列，贯通到 `PayoutCommand` |
| 4 | 无提现实名/KYB 校验 | ❌ 未做 | `IdentityVerifyClient` 仍仅 C 端注册用 |
| 5 | 缺银行预留手机号 | ❌ 未做 | `payout_account` 无该列 |
| 6 | 开票金额无「未开票冻结」 | ❌ 未做 | `InvoiceService` 仍是纯工单流 |
| 7 | `site_rent_bill` 无审核与付款凭证 | ⚠️ **V309 部分**：补了付款留痕（`paid_by`/`paid_voucher_no`/`paid_remark`），**但仍无审核环节** | |
| 8 | 无保证金/押金模型 | ❌ 未做 | `depositCents` 仍只用于预授权额度 |
| 9 | 通道费率硬编码 0.6% | ✅ **V309 已完成** | `fund.channel_fee_bps`（整数 bps + 钳制） |
| 10 | 无到账时效/截单配置 | ❌ 未做 | `WithdrawPolicyResolver` 无 timeliness |
| 11 | 无打款退票/查单终态回查 | ❌ 未做（**阻塞于支付认证**） | 三个 `transfer()` 均未实现查单 |
| 12 | 银行代付单日限额 | ⚠️ `channelLimits()` 已接（V308），但 `BankPayoutChannel` 仍恒 `return 0L`（未签约无额度） | |
| 13 | 微信分账 30% 上限未校验 | ❌ 未做 | `RevenueSplitService` `merchantShare` 可达 100% |
| 14 | 无分期/按量计费账单 | ❌ 未做 | |

**小计：4✅ / 2⚠️ / 8❌**

### 2.2 仓储与补货域（9 项，此域 A 方大幅领先）

| # | 缺口 | 状态 |
|---|---|---|
| 1 | 货损/盘亏责任归属缺失（无 merchantId/supplierId/理赔单号） | ❌ |
| 2 | 仓库侧盘点差异无原因分类 | ❌ |
| 3 | 跨仓调拨在途无差异/损耗记录 | ❌ |
| 4 | 盘亏不联动供应商应付 | ❌ |
| 5 | 仓库侧无核销/报废 | ❌ |
| 6 | 仓库侧无近效期预警阈值 | ❌ |
| 7 | 采购退货无原因分类/残次品处置 | ❌ |
| 8 | 供应商无对账单 | ❌ |
| 9 | 仓库月结不接财务结算 | ❌ |

**小计：0✅ / 9❌**（此域 9 项全未动，**是当前最大的整块空白**）

### 2.3 设备运维与识别域（12 项）

| # | 缺口 | 状态 |
|---|---|---|
| 1 | 真机不实现 4/5 运维指令 | ✅ **V308 已完成**（5 类全回 ACK） |
| 2 | 设备无自检能力 | ⚠️ **部分**：`SELF_TEST` 骨架 + V310 扩充 3 项（共 9 项可测）；**货道电机/主板温度/压缩机测不了**（无协议，且不能破坏性探测） |
| 3 | 云端视觉生产不可用 | ✅ **不再需要** —— 云端视觉路线已放弃（改将邑端侧 + HTTP 直报） |
| 4 | APK 硬编码共享凭据 + debug key + cleartext + OTA 不验签 | ❌ **未做，且是唯一「上线必暴雷」项** |
| 5 | 无远程配置下发 | ❌ |
| 6 | 无设备健康评分/预测性维护 | ⚠️ `network_rssi` 写入方已补（V310 自检），但无评分模型 |
| 7 | 耗材（打印纸/电池）零管理 | ❌ |
| 8 | 货道级故障无独立遥测 | ❌（需硬件协议） |
| 9 | 无坏机自助诊断 | ⚠️ `SELF_TEST` 是基础形态，无「诊断建议」 |
| 10 | 灰度无分批推进语义 | ❌ |
| 11 | `OFFLINE_AFTER_MINUTES=2` 硬编码 | ❌ |
| 12 | Kafka 识别入口绕过 deviceId 校验 | ❌ |

**小计：1✅ / 3⚠️ / 8❌**

### 2.4 营销会员与三端（7 项 + 6 决策项）

| # | 缺口 | 状态 |
|---|---|---|
| 1 | 广告收益对账/入账 | ⚠️ 展示链路已接腾讯流量主，**收入侧零上报**（见 §2.5，流量主路线下升为阻塞项） |
| 2 | 分享/邀请裂变归因与奖励 | ❌ `inviteCode`/`invitedBy` 仍是死字段 |
| 3 | 抽奖/互动游戏 | ❌ |
| 4 | 券可用范围维度 | ❌ |
| 5 | 触达渠道仅站内信 | ❌ |
| 6 | 会员等级权益仅文案 | ❌ |
| 7 | 营销 ROI 看板单维度 | ❌ |
| — | 6 项功能开关 | ⚠️ **已实现**，是运营决策不是开发欠债（其中 2 项因流量主路线变成「要上线就得开」） |

**小计：0✅ / 1⚠️ / 6❌**

### 总账

| 域 | ✅ 完成 | ⚠️ 部分 | ❌ 未做 |
|---|---|---|---|
| 资金结算 | 4 | 2 | 8 |
| 仓储补货 | 0 | 0 | 9 |
| 设备运维 | 1 | 3 | 8 |
| 营销三端 | 0 | 1 | 6 |
| **合计** | **5** | **6** | **31** |

**⇒ 真实待做 31 项，不是 66。**已完成 5 项、部分 6 项。
其中**立刻能做且不依赖外部的**只有：APK 凭据体系（1 项，但需你拍板方向）、
分享裂变（1 项）、券范围维度（1 项）等 —— 而**仓储补货整域 9 项零进展**，
是当前最被忽略的整块。

---

## 12. 🔴 取证 `ego-automat-android`：弹簧机的真实识别机制是**称重传感器**，不是视觉

2026-10-07 用户拉到旧系统安卓端（`D:/ideaCode/ego-automat-android`，
HEAD `6dc9198 versoin: 4.3.11`，216 个 kt/java 源文件）后逐文件取证，**结论推翻了一个隐含假设**。

### 12.1 规模与结构
| 项 | 事实 |
|---|---|
| 源文件 | 216 个（`app/src`），多模块：`android_serialport_api` / `greendao_generator` / `lib` / `keystore` |
| 厂商驱动 | **3 套并存**：`chzh8`（723 行，我们用的是这套）/ `jinyu2`（2127 行）/ `yichu2`（867 行） |
| 串口开门命令 | `ChzhDevice8.java:412` `String command = "L1@200" + "\r\n";`（同款`L1@200`，与我们 `ChzhLockDriver.kt:94` **完全一致**） |

### 12.2 🔴 核心发现：它靠**称重传感器**判断「用户拿了什么」
`ChzhDevice8.java` 里没有 YOLO、没有摄像头识别，取货判定是**每个货道的重量差**：

```
// ChzhDevice8.java:246-248
float caculWeight = initWeightData.get(tag) - (Float.parseFloat(abStrData) * 1000);
weightData.put(tag, caculWeight);

// ChzhDevice8.java:294（注释原文）
// 初始重量和当前重量差值（精确到 2% 左右）在 30以上:拿走的商品，-30以下:放进去的商品
if (weightData.get(aisle) >= 20 || weightData.get(aisle) <= -20) { ... }
```

流程是**开门时记录每个货道的初始重量 → 关门后延迟 2 秒持续读重 → 差值 ≥20g 视为「拿走」**。

⇒ **这不是「视觉识别」，是「重量传感」**。两个关键推论：

1. 🔴 **它无法识别「拿走了哪一件具体商品」**，只知道「第 3 货道轻了 250 克」。
   要落到 SKU 必须靠**货道→商品的绑定关系**（我们后端的 `slot` 模型），
   而不是识别画面。所以**我们后端的货道-商品映射比视觉更关键**。
2. 🔴 **它天然测不出「放回去」**（代码只处理 ±20 阈值，
   `-30以下:放进去的商品` 这句注释有，但未见对应的负向业务处理）。
   开门柜场景下「放回」是常见动作，这条路径缺失。

### 12.3 🔴 这对「改造成开门柜 + 将邑识别」的影响
**弹簧机改开门柜后，称重传感器不再是唯一通道**，但要判断**要不要留**：

| 方案 | 说明 | 风险 |
|---|---|---|
| **纯视觉（将邑）** | 去掉称重，靠开门/关门两帧识别算增减 | 视觉拿不到「具体是哪一件」，只能靠 SKU 识别；<br>多件同款、遮挡、手持都会错 |
| **称重 + 视觉双通道** | 称重给「第 N 货道变化量」，视觉给「具体 SKU」 | 需要两者对账，不一致时以谁为准要定|
| **纯称重** | 沿用旧逻辑 | 开门柜无货道（用户在门口取），**称重失去货道归属** ⇒ 不可行 |

⇒ **我的判断：弹簧机改开门柜必须走「视觉为主」，称重传感器在开门柜形态下
失去货道归属，无法沿用。** 这条要在与将邑沟通时讲清楚 ——
否则对方会以为我们有称重通道可以复用。

### 12.4 门与称重的时序耦合（改造时最容易踩的坑）
`ChzhDevice8.java:177-189`：
```
// 关门后延迟一秒再传关门信息，让重量继续读取。
Observable.timer(2000, TimeUnit.MILLISECONDS)
        .subscribe(aLong -> { Num8DeviceData result = Num8DeviceData.doorResult(false, returnWeightData); ... });
```
⇒ **关门瞬间读重量是不准的**，必须等约 2 秒让称重稳定。
若开门柜改造后照搬「关门即结算」，会在重量未稳定时取数 ⇒ 差值偏小 ⇒ 少算购入。

我们 V308 的 `handleSelfTest` 里已有一条同源教训：
`doorState` 早关会把用户锁在流程外、晚关让会话一直挂着。
**时序问题是这类设备的通用坑，不是我们独有的。**

### 12.5 给将邑的清单（更新版，替换我上一轮的说法）
**要给的**：
1. **串口协议现状**：`L1@200\r\n` 开门 + `DOOR=C/0/CLOSED` 回包解析（`ChzhLockDriver.kt`）
2. **云端协议事实来源**：`MqttTopics` + `MqttDeviceClient`
3. **云端已就绪上报端点**：`POST /internal/v1/vision/edge-results`
4. 🔴 **旧安卓端的货道称重协议**（`ChzhDevice8.java:246/294`，含±20g 阈值与 2 秒延迟）

**不用给的**：`service` / `ota` / `upload` / `video`（应用层编排，与硬件无关）。
⚠️ `vision/` 已于2026-10-07 删除（零调用方 + 逻辑倒置），不存在「破坏对接」的风险。

**采购三问**（更新）：
1. 中间件部署在原 Android 主板（aar/SDK）还是独立边缘盒（HTTP）？
2. 🔴 **是否给「开门帧/关门帧」两帧**？只给最终 SKU 列表则
   `SkuDeltaCalculator` 的两帧差分用不上（这条不变，但理由现在更硬 ——
   旧系统证明了两帧差分是可行的行业做法）。
3. 🔴 **称重通道要不要留**？见 12.3 分析 —— 开门柜形态下建议去掉。

### 12.6 未核实项
`jinyu2`（2127 行）与 `yichu2`（867 行）两套驱动**未逐行读** ——
如果新柜机是这两个厂商的型号，协议可能完全不同。**采购时须先确认柜机型号**。

---

## 13. 仓储补货域 9 项逐条核实（2026-10-07）：**2 项文档说缺但实际已有**

被 `ego-automat-android` 取证触动 —— 既然 vision 层能「整层都是死代码」，
清单也可能有过期项。逐条 grep 后**证实 2 项不成立**：

| # | 缺口 | 核实结论 |
|---|---|---|
| ① 货损责任归属 | ❌ **真缺** | `InventoryWriteOff` 只有 deviceId/skuId/batchNo/quantity/reason/costCents/operatorId，**无 merchantId/supplierId/理赔单号** |
| ② 盘点差异无原因分类 | ❌ **真缺** | `WarehouseService:284` reason **硬编码 `"STOCKTAKE"`**，差异无分类 |
| ③ 跨仓调拨在途无损耗 | ❌ **真缺** | `WarehouseTransferLine` 只有 skuId/batchNo/expiry/quantity，**无 LOST/DAMAGED** |
| ④ 盘亏不联动供应商应付 | ❌ **真缺** | `SupplierPayableService` 只 import PaySupplier/SupplierPayable，**无 writeOff 引用** |
| ⑤ 仓库侧无核销/报废 | ❌ **真缺（但比文档描述的好）** | `InventoryOpsService.writeOff` 有完整实现（`InventoryWriteOffMapper` 都在），但入口 `deviceValidationService.requireDevice(request.deviceId())` ⇒ **只支持设备侧，仓库侧确实没有**。文档「仅设备侧」表述准确 |
| ⑥ 仓库侧无近效期预警阈值 | ⚠️ **文档不成立** | `OpsReplenishmentController:253` 与 `MerchantPortalController:272` **都有 `expiryAlerts` 端点**；`SkuCatalog:64` 有 `nearExpiryDays = 7` + `nearExpiryPriceCents`。⇒ 不是「只有索引没有预警」，而是**已有端点**，需核实是否覆盖仓库维度 |
| ⑦ 采购退货无原因分类/残次品处置 | 待核 | — |
| ⑧ 供应商无对账单 | 待核 | — |
| ⑨ 仓库月结不接财务结算 | 待核 | — |

### 13.1 🔴 方法论：这份清单必须逐条重核，不能照着改
`ego-automat-android` 那次取证暴露了一件事：
**「文档说缺」和「实际缺」是两件事**，而我此前的工作方式是**先写清单、后按清单施工**。
这轮 vision 层（整层死代码）与 ⑥（端点已存在）都证明清单会过期。

⇒ 后续动手前**每项先 grep 一次**，代价是 5 分钟，省掉的是「改完才发现本来就有」。

⚠️ 风险面：§11 的清单还剩 31 项，**可能还有类似过期项**。
本节只核了仓储域 6/9 项，剩余 25 项**未核** ——
不能假设「它们都是真缺」。
