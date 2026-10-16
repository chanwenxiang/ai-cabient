# F-2 / F-3 / F-4 拍板简报（2026-10-16）

> 触发：B-1 扩展审留下三条待拍板（`docs/AUDIT_B1_EXTENDED_FUND_2026-10-16.md` §2）。
> 用户指令：「F-2 你看看老代码和竞品；F-3 流量费应该是老系统有通信卡的，我们向租我们柜子或者买我们柜子的人收的，
> 好像是按天算的，金额可以做成按照货柜配置；F-4 老系统好像会有负数，你看看老代码和竞品。」
> 纪律：本简报**只做取证与选项**，不替用户改资金口径（规则 `critical-judgment` + 用户「不明白先停下来问」铁律）。
> 老系统证据全部**主会话亲读源码复核**（两路子代理自报后经我逐行验证），竞品证据标注来源等级。

## 0. 一句话

F-2 与 F-4 的正确答案取决于 F-3：**费用链现在并存两套方向不一致的模型**（向商户日扣 vs 平台应付台账），
先把「流量费向谁收、什么单位」定死，另两条只剩一个自然解。

## 1. 老系统实证（`D:\ideaCode\easygo`，行号=我亲验）

| # | 事实 | 证据 |
|---|---|---|
| L1 | **通信费真实存在且按天**：任务名叫「流量费」，逐台柜机每天扣一次，无任何除以当月天数的摊销 | `ego-automat/.../mobilecard/domain/MobileCardFeeGenerationTask.java:160`（`setFeeName("流量费")`）、`:148`（`addMoneyToAccount(userId, -amount)`） |
| L2 | **金额按柜机配置**，回退顺序=该机配置 → 该商户标准费 → 全局 100 分/天 | `MobileCardFeeGenerationTask.java:107`（`findByMachineId`）、`:112-124`、`ConstantUtils.java:5`（`STANDARD_TRAFFIC_CHARGES_FEN = 100` //标准机器流量费（分））；配置表 `ego_opt_machine_mobilecard_charge_config` 有 `unique (machine_id)`（`ego-automat/doc/db/M9_optimization.sql:31-38`） |
| L3 | **向谁收=柜机所有人，租赁与购买不区分**：只按账户类型取「个体户 + 企业户」，遍历其名下**每一台**机器，全程不读 `machine_use_type`（RENT/SALE/ENTIRE_RENT/HALF_RENT…） | `MobileCardFeeGenerationTask.java:91` + `ConstantUtils.java:9`；`EgoMachineBaseInfoMapper.xml:342-348` 定义 use_type 枚举但本任务不引用。租金是**另一笔按月费**（L6） |
| L4 | **扣款记录即收据，没有「未付/已付」状态位**：三张费用表都**没有 status 列**，幂等靠存在性检查 + **DB 唯一键** | `M9_optimization.sql:5-28`（`ego_opt_machine_basicfee` `unique(user_id,date,fee_type)`、`ego_opt_machine_mobilecard_charge` `unique(machine_id,date)`）；`MobileCardFeeGenerationTask.java:130-133`（查到同日同机即 `continue`） |
| L5 | **顺序是「先扣到钱，才写行」**，扣款失败就不留费用行 ⇒ 老系统不可能出现「钱扣了还挂着欠」 | `MobileCardFeeGenerationTask.java:148-162`（`isSuccess` 为假直接 `continue`） |
| L6 | 租金按月扣，**只有首月**按剩余天数折算；幂等同 L4（存在即跳过） | `servicefee/timer/ServiceChargeTask.java:57-72`（`rentAmount = round(rent/maxDay*remDay)` 仅在 `createDate==billDate` 分支）、`:81`（`deductMoneyFromAccount(userId, rentAmount*-1)`） |
| L7 | **余额可以为负（挂账）**：扣款原语没有下限 | `opertion/domain/service/OptAccountService.java:1473,1482`（`cashAvailable = 现值 + 增量`，无校验；`@Version` 乐观锁重试 3 次） |
| L8 | 负的代价=**禁提现**，不是锁柜：`0 > 余额` 直接 `BALANCE_LIMIT`；另有运营手设的提现开关 `whetherWithdrawals` | `operation/withdrawal/controller/WithdrawalController.java:200-205`（`:376` V2 同口径） |
| L9 | **老系统没有「欠费→锁柜/停售」代码**：停售是人工按钮或运营商物联卡状态回传（到期停机/超流量停机） | `m9managepro/.../MachineListController.java:143`（人工暂停售卖）、`mis-web/src/views/operation/iotCardInfo/index.vue:30-31`（停机状态来自卡侧，非平台费用侧计算） |
| L10 | **漏跑某天的补扣=人工带日期重跑**：批处理 jar 是命令行分发器，`f` 模式接受显式 `date`（内部再减一天），cron 不带日期=默认算昨天；因为幂等键在库里，重跑安全 | `ego-automat-bill-timer/src/main/java/.../Application.java:99-103`、`mobilefee_start.sh`（无日期参数）、`ego-automat-bill-timer/README.md:18`（`1 4 * * *`） |

**结论（老系统侧）**：用户的记忆三点全部为真（有通信卡费用 / 向租与买的柜机所有人收 / 按天 / 可按柜配置），
并且老系统对 F-2 给的是**唯一明确答案**：扣款成功才算出账，行即收据，绝不双记；F-4 的负数挂账也是老系统同款。
老系统**没有**任何「已扣钱仍挂应收」或「欠费自动锁柜」的先例——我们的这两处是新增设计，不能声称「老系统同款」。

## 2. 竞品 / 官方实证（来源等级已标）

| 主题 | 证据 |
|---|---|
| 柜机通信费向运营商/购机方收、按台按期 | 智购科技公开报价「流量费 228 元/台/年」+「0.6% 手续费直接从交易额中扣除」（UNKNOWN，厂商自媒体 [toutiao](https://m.toutiao.com/article/7591027065583845931/)）；方海智能「第二年起每月收 50 元物联卡服务费」、网络通信成本 50–200 元/月/台（UNKNOWN [baijiahao](https://baijiahao.baidu.com/s?id=1818286263524698734)）；海外智慧售货机行情「每月 SIM/遥测费 30–100 美元」（MEDIA [Shopify](https://www.shopify.com/sg/blog/how-to-start-a-vending-machine-business)） |
| 反例（自营模式不收） | 丰e足食官网「企业 0 成本入驻，无押金、无服务费」（OFFICIAL [feng1.com](https://www.feng1.com/news/33)，已亲验原话）——直营制与加盟制的费用承担方天然不同 |
| 平台费主流=**结算/出款时代扣，不产生应收单** | 微信支付分账官方：分账在冻结资金内切分，「分账总额不能超过订单金额减去手续费的净额」，冻结期最长 30 天，剩余自动解冻（OFFICIAL [pay.weixin.qq](https://pay.weixin.qq.com/doc/v2/merchant/4011941604)）；微信侧明确「分账冻结的不可用余额不会生成独立记录」⇒ **扣款流水本身就是凭证，没有 unpaid-bill 对象**（OFFICIAL/社区 [developers.weixin](https://developers.weixin.qq.com/community/pay/doc/00004294ec820046a8143169269400)） |
| 欠费不停机、下一账期自动扣 | 微信支付分运营版官方原话「**欠费后不停机，次月出账后，自动扣除先享额度**」（OFFICIAL [solution 页](https://pay.weixin.qq.com/static/solution/solution_detail_operation.shtml)，已亲验原话） |
| 欠费停服阈值=**时间**，且充值后**自动复机** | 阿里云物联网平台：欠费后旧版公共实例 24 小时停服，「完成充值或续费后，最多需要 30 分钟，业务就能完全恢复正常使用」（OFFICIAL [alibabacloud](https://www.alibabacloud.com/help/zh/iot/product-overview/overdue-payments-and-renewal-methods)，已亲验）；天翼云 冻结→15 天内充值自动解冻（OFFICIAL [ctyun](https://www.ctyun.cn/document/11057595/11064262)）；腾讯云/阿里 SOFAStack 为**手动**复机（少数派，OFFICIAL [tencent](https://cloud.tencent.com/document/buy-guide/582/9554)、[aliyun](http://help.aliyun.com/document_detail/366427.html)） |
| 先享后付「欠款」的真实处置=**挡住下一次开门**，不是锁整机 | 微信支付分：未完结/待支付订单拉低下次评估（OFFICIAL [FAQ](https://pay.weixin.qq.com/doc/v3/partner/4013757943)）；芝麻先享：免密代扣轮询资金渠道 + 奖惩机制（OFFICIAL [产品页](https://b.alipay.com/page/product-mall/product-detail/I1080300001000056813)） |
| 柜机厂商的「欠费锁机」公开规格 | **未检索到公开规格**（友宝/哈哈零兽/创弗/映翰通/Nayax 均未公布；只有反向案例=平台扣运营方货款）。故现有 `merchant.overdue.lock_months` 属**我方设计判断**，竞品台账不得写「行业主流」 |

**结论（竞品侧）**：①通信费按台按期、由柜机所有人承担 = 行业主流；②「扣了钱还挂应收单」= 未发现任何先例；
③欠费处置的主流是「时间阈值 + 结清后自动复机」，而我们是「金额阈值 + 人工标记结清才解锁」；
④「按金额阈值锁整台柜」没有公开对标，风险应说成设计判断而非行业惯例。

## 3. 我们现状（行号亲验）与三处矛盾

| 现状 | 位置 |
|---|---|
| 每日从商户钱包**真扣** 租金+流量费（月额 ÷ 当月天数），并**另写一条 PENDING 台账行** | [MerchantDailyIncomeNotifyScheduler.java:278-308](../services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantDailyIncomeNotifyScheduler.java) |
| PENDING 累计作催缴与锁柜判据，阈值=月费 × `lock_months`（默认 1），结清只能调 `settle` | 同文件 `:200-235`、`MerchantFeeLedgerService.java:37-56`、`OpsMerchantFeeLedgerController.java:30-40` |
| 流量费另有**月度账单**体系（`device_data_fee_bill`，UNPAID/PAID/VOID，人工「标记已付不触发自动扣款」），其自动出账 job 默认关 | `V256__device_data_fee_bill.sql`、`DeviceDataFeeBillService.java:135-158`、`OpsFeeBillJob.java:44-49`（`feebill.auto_generate.enabled` 默认 false） |
| 后台字段口径=**元/月** | `clients/admin-vue/src/components/device/DeviceAssetDeploymentCard.vue:293`（label「流量费（元/月）」）；列注释只写「datafee金额（分）」无单位（V127/V237） |

- **矛盾 1（方向）**：竞品台账 2026-10-12 条目记的是「流量费=平台月结应付台账，方向与旧系统相反——平台付 ISP，非向商户收」，
  而 2026-10-14 上线的日摊却**真向商户扣钱**；`DeviceRentalService.java:36` 的注释也写着「DeviceDataFeeBill（平台付流量费）」。
  老系统实证（L1/L2/L3）站在「向商户收」一边 ⇒ 台账里那句关于新系统的表述已成历史，两套模型现在同时存在。
- **矛盾 2（单位）**：老系统是**分/天**原生（L1/L2），我们是**元/月 ÷ 当月天数**（F-6 整数除法余数永不闭合，月末会少收 1..28 分）。
- **矛盾 3（范围）**：日摊只在 `coopMode=RENTAL` 分支里累加 `dataFeeCents`（`:287-295`），
  而锁柜阈值 `monthlyFeeTotal`（`:340-351`）对**所有**设备加 `dataFeeCents` ⇒ 买断柜的流量费既没扣、又抬高欠费阈值（F-3 的实际形态）。
  老系统 L3 是「不分租赁/买断，全部按天收」，与我们的两处都不一致。

## 4. 选项与后果

### Q1 F-3 流量费口径（方向 + 单位 + 范围）
- **A（推荐，= 老系统 + 竞品主流）**：`device_info.data_fee_cents` 语义改为**分/天**，对**所有**有柜商户每日照扣（不再只看 RENTAL），
  迁移只需改列注释 + 现有值换算（当前库内 `data_fee_cents>0` 的设备 **0 台** ⇒ 零数据风险），UI label 改「流量费（元/天）」并给默认 1.00 元/天。
  代价：改注释/迁移 + 1 处 UI + `chargeDailyPeriodFees`/`monthlyFeeTotal` 口径统一；好处：F-6 余数问题天然消失（不再除天数）。
- **B（维持元/月 ÷ 天数）**：改动最小，但要单独修 F-6（月末补差），且与用户记忆里的老系统「按天」不一致。
- **C（回到 V256 月结账单人工收款）**：与 2026-10-14 用户已拍板「余额肯定是减掉费用后的」相冲，且竞品侧未发现先例支持「靠人工标记已付」。

### Q2 F-2 台账与钱包的关系（现唯一必须修的资损向问题）
- **A（推荐，= 老系统 L4/L5 + 微信分账语义）**：`扣款成功 ⇒ 台账行直接 SETTLED（行即收据）`；
  只有**扣款失败**（钱包异常）或**运营选择线下挂账**时才留 PENDING 作为「应追缴」；催缴与锁柜判据只看 PENDING。
  代价：`chargeDailyPeriodFees` 写行时带状态 + 一处幂等口径；**必须同时补 UI**，否则线下收的钱无处可标：
  运营台加「商户费用台账」页（列表 + 标记结清，复用已有两个端点）。
- **B（只登记不扣）**：与 10-14 拍板冲突，商户每天提现提走的是没扣费用的钱 ⇒ 平台净亏；不推荐。
- **C（维持现状：真扣 + 永远 PENDING）**：等于「同一笔费用收一次、按欠费再锁一次柜」，
  一旦运营填入租金（现在 0 台，上线第一天就可能非 0）约一个月后**必然全量锁柜**。不可接受。
- 附带的锁柜判据问题（竞品 §2 第 5 行）：**金额阈值 + 人工结清** vs **时间阈值（欠费满 N 天）+ 结清自动复机**。
  我倾向后者（对标阿里云/天翼云，且 `unlockFeeOverdueIfCleared` 已具备自动解锁能力），但这是新增能力的参数，请你定。

### Q3 F-4 负数与漏扣
- 负数挂账：**老系统 L7/L8 + 竞品 §2 第 4 行都支持保留**（余额可负、禁提现、不锁柜），我们现在也正是这个行为
  （`MerchantWalletService:719-728` 提现要 `可用余额不足` 直接 412）⇒ **无需改动**，只需把「老系统同款」的表述改准（老系统确实无下限，但没有锁柜）。
- 漏扣那天要不要补：
  - **A（推荐，= 老系统 L10）**：不做自动回补；把注释「失败商户次日重跑补扣」改成事实（**不会自动补**），
    并给运营一个「按日期重跑日摊」的手动入口（现有任务已有 `tryBegin` 租约与幂等 refId，重跑安全）。
  - **B（自动回补）**：每次运行扫描最近 N 天缺口逐日补扣 ⇒ 需要新的缺口检测与多日 refId，复杂度明显上升，且老系统/竞品都没这么做。

## 5. 待你拍板（我不自行选）

1. **F-3**：选 A/B/C？（A=改成「分/天」+ 所有柜机照收 + 默认 1 元/天）
2. **F-2 主口径**：A 扣款成功即结清（PENDING 只留给真实欠费），还是 B/C？
3. **F-2 锁柜判据**：维持「欠费金额 ≥ 1 个月费用」，还是改「欠费满 N 天（N=？）」并结清后自动复机？
4. **费用台账 UI**：这轮就补运营台页面（我做），还是先只做后端口径、UI 排到上线后（期间必须把锁柜默认关掉）？
5. **F-4**：漏扣走 A（手动按日期重跑）还是 B（自动回补）？
6. 顺带确认：老系统「流量费不分租赁/买断都收」是否照做（若你只想对 RENTAL 收，我就把 `monthlyFeeTotal` 与日摊两侧对齐成 RENTAL-only，那是**有意偏离老系统**，需另记 CB）。

拍板后我再动代码：改动会连带 `V380`（列注释/换算）+ 竞品台账 CB-041 修订 + 台账里那句「平台付 ISP」的勘误。

## 6. 追加取证（2026-10-16 用户第二次追问）：「这些费用可以为 0 = 不收 / 没有」

老代码（`D:\ideaCode\easygo`，行号亲验）：

| # | 事实 | 证据 |
|---|---|---|
| Z1 | **流量费 0 是合法配置值**：只有**负数**才被兜底成 100 分，0 原样透传 ⇒ 老系统可以表达「这台柜不收流量费」 | `MobileCardFeeGenerationTask.java:112-116`（`if (amount < 0) amount = 100;`，注意 `amount > 200` 那条判断被注释掉了） |
| Z2 | **0 元当天仍写一行**：扣款原语对 0 也返回成功（`cashAvailable = 现值 + 0` 照常 save），随后落一条 `amount=0` 的流量费行 ⇒ 行是「今天这台已核算」的存在性幂等键，不代表欠费 | `:148` → `OptAccountService.java:1489,1504`（`return !isSaveBlocked`）→ `:155-162` |
| Z3 | **商户日汇总即使全天空费也写 0 行**：`ego_opt_machine_basicfee.amount_total=0` 照样落表 | `MobileCardFeeGenerationTask.java:211-238` |
| Z4 | **租金/巷道费用「配置开关」表达不收**：只有 `status='Y'` 且有配置行的柜机才进循环；停收=把 `status` 置 `N`（无行 / N = 完全不遍历，不是写 0） | `ServiceChargeTask.java:47`、`:127`、`closeServiceFeeConfig :206-223`；`MachineRentalConfig.java:30`（status 字段） |
| Z5 | 🔴 **老系统的反面教材**：配置行存在但 `amount=NULL` 时，`:114` 的 `amount < 0` 触发 `Integer` 拆箱 NPE，`forEach` 无捕获 ⇒ **整批任务中断**。我们侧 `data_fee_cents` 为 null 必须显式当「不收」，绝不能进扣款路径 | `:107`+`:114`；模型 `OptMachineMobilecardChargeConfig.java:25`（`Integer amount` 无 `nullable=false`） |

竞品/官方：

- 阿里云官方公告《应付金额为 0 的账单查询能力升级》：0 元账单**照样生成**（两种场景：单价 0 元但用量不为 0、官网价 0 的新购/续费），控制台与导出给「是否包含 0 元账单」开关，API/OSS 订阅默认包含 ⇒ **0 行是记录，不是欠费**（OFFICIAL [help.aliyun.com](https://help.aliyun.com/zh/user-center/product-overview/billing-query-upgrade-announcement)）。
- 阿里云费用与成本 FAQ：**「账户可用额度 =（现金余额+信控额度）−（当月未结清+历史未结清），若账户可用额度小于 0，即视为欠费」**⇒ 0 不算欠费；同页「免费额度没有使用完的话是不会出账产生费用的」⇒ **没有真实费用就不出账**（OFFICIAL [help.aliyun.com](https://help.aliyun.com/zh/model-studio/billing-for-model-studio)）。
- Stripe：`$0` 发票不自动发收据（"Stripe does not send a receipt if a charge has not been made"）；**应付金额低于该币种最小起结额时发票自动标记为 paid**、余额转入 credit balance ⇒ 小额/零额**不进入催收链路**（OFFICIAL [support.stripe.com](https://support.stripe.com/questions/does-stripe-send-receipts-for-0-invoices)、[docs.stripe.com/api/events](https://docs.stripe.com/api/events/list)）。
- Cloudflare：「You will receive a $0 invoice even if your domain is on a Free plan」⇒ 免费/零额仍出账，纯记录用途（OFFICIAL [developers.cloudflare.com/billing](https://developers.cloudflare.com/billing/llms-full.txt)）。
- 欠费停服时间阈值官方先例（支撑 §4 的 N=7）：阿里云 AI 网关「欠费未超 5 天可继续使用并正常计费，超出则停机，**补足欠款后可正常恢复使用**」（OFFICIAL [help.aliyun.com](https://help.aliyun.com/zh/api-gateway/ai-gateway/product-overview/description-of-arrears)）；开放搜索「欠费满 **7 个自然日**后停止服务」、弹性容器实例「欠费满 7 日释放」，充值结清即恢复（OFFICIAL [terms.aliyun.com](http://terms.aliyun.com/legal-agreement/terms/suit_bu1_ali_cloud/suit_bu1_ali_cloud201802281451_77479_29_16_20743.html)）。

**落到实现的口径（用户诉求=0 无任何负面后果）**：

1. 「不收 / 这台柜没有卡」用 `device_info.data_fee_cents` = **0 或 NULL** 表达，是一等正常态：UI 明示「0 = 不收取」，**不给任何默认费率**（撤回 §4 Q1-A 里「默认 1.00 元/天」那句——那会把「不收」变成「收」）。
2. 0 与 NULL 一律**不进扣款路径**（避 Z5 的 NPE 型缺陷）、不写台账行、不进欠费判据、**永不锁柜**；时间阈值判据前置条件=「确有未结清金额 > 0」。
3. 台账行粒度：老系统/阿里云写 0 行，是因为它们的粒度是**逐台/计费项明细**（0 行=「这台今天核算过了」）；我们的 `merchant_fee_ledger` 粒度是**商户×日×费用类型**，写 0 行只剩噪音。故按噪音更小的「0 不写行」实现；若你要逐柜留痕，那是改表粒度，另立需求。

## 7. 拍板结果（2026-10-16，用户四问全按推荐口径 + 追加参数）

| 项 | 结论 |
|---|---|
| F-3 流量费 | **A**：`device_info.data_fee_cents` 语义改「分/天」，**全部柜机照收**（不再只看 `coopMode=RENTAL`），日摊不再除天数（F-6 余数问题在流量费侧天然消失）；0/NULL=不收（§6） |
| F-2 台账语义 | **A**：扣款成功 ⇒ 台账行直接 `SETTLED`（行即收据）；扣款异常当日**不留行**（老系统 L5）；`PENDING` 只代表真实欠费 |
| 锁柜判据 | **时间阈值**：存在未结清欠费且最老一行账龄满 **7 天**（`sys_config merchant.overdue.lock_days=7`，替代 `lock_months`）⇒ 锁柜；结清后 `unlockFeeOverdueIfCleared` 自动复机（阿里云/AI 网关「补足欠款可正常恢复」、开放搜索「满 7 个自然日停服」同款先例） |
| F-4 漏扣 | **A**：不做自动回补；注释改成事实（不会自动补），运营台加「按日期重跑日摊」手动入口（幂等靠 `refId=日期` + `uk_merchant_fee_date_type`） |
| 台账 UI | 本轮一并补运营台页面（复用 `OpsMerchantFeeLedgerController` 的 list / settle 两个端点，权限码 `ops:merchant-withdraw:list` / `:adjust` 已种子，无需新迁移） |

## 8. 落地状态（2026-10-17 复核，源码+实跑，非自报）

| 拍板项 | 落点 | 证据 |
|---|---|---|
| F-3 分/天、全柜机照收 | `V380__data_fee_per_day_and_settled_on_debit.sql` + `MerchantDailyIncomeNotifyScheduler.chargeDailyPeriodFees`（枚举全部有柜商户，不除天数） | 单测 `purchasedDeviceIsChargedDailyDataFee`、`dataFeeDoesNotDependOnMonthLength` |
| F-2 扣款成功即 SETTLED / 异常不留行 | 同上迁移 + `chargeDailyPeriodFees` 写行时序（先扣钱包、异常向上抛 ⇒ 无行） | `successfulDebitWritesSettledRowsNotPending`、`debitExceptionLeavesNoLedgerRow` |
| 0/NULL=不收 | 判据一律 `!= null && > 0`；`feature-flags.json` 与 `SystemConfigService` seed 同步（`feebill.auto_generate.enabled=false`、`device.rental.monthly_bill.enabled=false` 防与日扣重复计费） | `zeroAndNullDataFeeChargeNothingAndWriteNothing` |
| 账龄锁柜 + 结清自动复机 | `checkOverdueAndRemind` 读 `merchant.overdue.lock_days`（默认 7）；解锁只解 reason 前缀「费用欠缴锁柜」；旧 `lock_months` 已从注册表删除（R5 不认 deprecated 豁免，留着就是死旋钮） | `feeDebtYoungerThanLockDays_remindsButDoesNotLock`、`feeDebtAgedLockDays_locksDevices`、`feeDebtCleared_unlocksOnlyOverduePrefix`、`negativeBalanceButNoFeeDebt_neitherRemindsNorLocks` |
| F-4 手动按日期重跑 | `POST /api/v2/ops/admin/merchant-fee-ledgers/rerun-daily-fees?feeDate=YYYY-MM-DD`（`OpsMerchantFeeLedgerController:63`，权限 `ops:merchant-withdraw:adjust`）；守卫=**不晚于昨天** + **回溯 ≤92 天**；`packages/shared-types/src/generated/openapi.ts` 已用重建 jar 重生成对齐（门禁 `check:admin-endpoint-paths` 恢复绿） | `MerchantDailyIncomeNotifySchedulerTest#rerunForDateChargesOnceAndChecksOverdue` + `OpsMerchantFeeLedgerControllerRerunGuardTest`（5 用例：今天/未来/超 92 天/不可解析=400 且不落到扣款；昨天与第 92 天=放行各一次） |
| 台账 UI | `clients/admin-vue/src/views/finance/MerchantFeeLedgerView.vue`（路由 `merchant-fee-ledgers`、菜单「商户费用台账」含关键词「重跑日扣」）：列表/筛选 + 标记结清 + 「按日期重跑日扣」弹窗（默认昨天，成功提示「本轮实扣 ¥X，幂等不重扣」） | 门禁 `check:menu-permissions` / `check:frontend-perm-codes` / `check:admin-endpoint-paths` 全绿；`node scripts/run-audit-gates.mjs` 49 门禁 0 失败 |

**平台分账费率归 0（2026-10-17 已落地，原「等实证」阻塞点按设计绕开）**：用户 2026-10-16 拍板「先配置为 0，先不收」。真正卡住的不是写代码，而是**微信侧是否允许单笔分账 `amount == 订单全额`**——`WeChatProfitSharingService` 发出的正是商户那一份（`amount = split.getMerchantCents()`），费率归 0 后 ratio=100%，而 CB-032 记到的默认单笔分账比例上限是 30%（⚪ 多源旁证、提额真实性未与微信确认）。落地口径**不赌这个未实证能力**：0 抽成的单一律不发分账请求，直接 `LEDGER_ONLY` 把商户份额整笔入可提现钱包，出款走 CB-033 的商家转账通道。落点=V382（列默认 + 存量 2 家 1000/1500 ⇒ 0）+ Java 实体/`doUpsertMerchant` 缺省 + 运营台表单 3 处默认改 0 + 三道 0 抽成闸（`RevenueSplitService.doRecordSplit`/`doResyncSplitForOrder`、`WeChatProfitSharingService.prepareSubmit`）。**历史 `order_revenue_split` 行不回改**。日后真要抽成：先按商户显式填档位，再决定是否需要向微信确认提额——若届时 ratio 仍 >30%，同样会撞这条未实证边界，届时必须实证。


