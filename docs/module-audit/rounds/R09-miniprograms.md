# R9 双小程序（consumer-mp 23 页 / merchant-mp 35 页）

- **锚**：发现锚 `55bac20f`（R8 批 2 收口提交）；修复锚＝MA-023 展示侧 `a96b57c8`；MA-023②/024/025 未动码（待拍板）。
- **环境**：本机 dev 栈；**微信开发者工具 + miniprogram-automator**（`ws://127.0.0.1:9421`，consumer 项目 `dist/dev/mp-weixin`），判据按用户 2026-10-27 的口径——只看 mp 真端，H5 红不阻塞。
- **登录注入**：合成输入在 automator 上坏（记忆 `mp-automator-verify`），走 HTTP 短信登录（dev 验证码直读 `sms_verification_code`）+ `setStorageSync` 注 `consumer_token/consumer_user_id/consumer_token_expires/consumer_server_boot`，`mockWxMethod('scanCode')` 提供柜号。
- **进度**：consumer-mp **批 1+2+3 = 22/23 页**走完（第 23 页 `announcements/detail` 因 `announcement` 表 0 行无内容可看，留「造一条公告」后再点验）；**merchant-mp 35 页未开始**。本轮新领 MA-023～MA-028 六条，其中 MA-023 展示侧已修，其余五条待拍板。

## 批 1 逐页结论（consumer 主包 7 页）

| 页面 | 数据/口径核对（真值来源） | 交互与写面 | 结论 |
|------|---------------------------|------------|------|
| `pages/index/index` 柜内选购 | 商品卡渲染「雪碧330 · 活动价 · 原价 ¥3.50 · ¥2.43/罐」；但 `sku_catalog.price_cents=350`、活动 4 = `SKU_REDUCE` 减 100、会员 98 折 ⇒ 结算基准 343、实付 243。**划线价与活动减额算不平（差 0.07）** | 只读走查；未提交开门/下单（开门是副作用，见下「走查副作用」） | **MA-023（P2）展示侧已修，记账侧待拍板** |
| `pages/orders/orders` 我的订单 | 芯片 已完成 2 / 待支付 0 / 有疑问 4 / 已退款 0 / 已取消 0，`全部 2` 与「隐藏零元单」默认 ON 自洽：SQL `cabinet_order where user_id=10001` = 3 笔 PAID，其中 1 笔 `total_amount_cents=0` ⇒ 列表 2；`shopping_session` DISPUTED 5（含走查新建 2）对应「有疑问」侧 4 + 「还有 1 条待确认」提示 | 时间 chips（全部/今天/近7天/近30天）与「隐藏零元单」为筛选态；行内「查看详情」跳 order-detail（未进，留批 2） | 无缺陷（零元单口径与后台 MA-009 家族一致，页面自带开关交代） |
| `pages/mine/mine` 我的 | 余额 `¥140.00` 两处出现＝头部卡(:43-44) + 「可用余额/充值」入口(:49-58)，代码注释(:36) 已写明刻意同名；与 `member`/钱包读面一致 | 「优先支付方式」三选（余额/微信免密/支付宝免密）为写面，本批只看不点 | 无缺陷 |
| `pages/login/login` | 已登录态被守卫重定向回首页（正确） | — | 无缺陷 |
| `pages/verify/verify` 开通支付 | 已开通态显示「✓ 可以开门购物了 / 微信支付分 先取后付 / 优先支付 / 可用余额 ¥140.00」 | 未点「开通免密支付」（写面） | 无缺陷 |
| `pages/realname/realname` 实名认证 | 已实名态文案「已完成实名 / 提现打款资质已就绪」 | 未提交表单（写面） | 无缺陷 |
| `pages/result/result` 账单结果 | 用真单 `1793498414728440537903`：实付 ¥2.43 = `total/paid_amount_cents=243`；「活动优惠 减¥1.00」= `activity_discount_cents=100`；「本单已享活动价，不再叠加优惠券」= `coupon_discount_cents=0`；明细 单价 ¥2.43 ×1、批次 SIM-CB082、货道 A1 | 「账单有问题 / 申请退款 / 帮助」为入口，未点 | 无缺陷（CB-082 结果页三要素在真端齐） |

## MA-023（P2）会员折扣在「商品卡」和「订单账」两层都不现身

真值链（同一单，逐个来源钉）：`sku_catalog.price_cents` **350** → 会员 98 折 **343**（`MemberService.applyMemberPriceDiscount:377-395`，结算侧 `SettlementOrderSupport:90` 同一函数）→ 活动 4 `SKU_REDUCE 100` → **243**；订单行 `unit_price=243, activity_discount=100`，订单头 `total=243, paid=243, member_discount=0, coupon_discount=0`。排除了其它定价来源：`device_sku_price` 无该 SKU 行、`near_expiry_price_cents` 为空。

1. **展示侧（本批已修）**：`DeviceCatalogService` 把 `priceCents` 下发成**目录价**，而活动价按**会员后价**再减 ⇒ 商品卡「原价 ¥3.50」配「¥2.43/罐」，中间只写了「减 1.00」，差的 0.07 在页面上没有任何出处；非活动商品同样出现「显示 3.50、结算 3.43」。
   - 修复：[DeviceCatalogService.java:97-110](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/DeviceCatalogService.java#L97-L110) `priceCents` 改下发「本用户无活动时的应付单价」（会员价；无会员身份时仍等于目录价，`NearbyDeviceService` 走 `listProducts(deviceId)` → `userId=null` → 原价透传，不受影响）。
   - 判据：商品卡「原价 − 活动价」必须等于活动减额（343 − 243 = 100 ✓），划线价不再虚标。
   - 单测：`DeviceCatalogServiceTest.listProducts_priceCentsIsWhatThisUserPaysWithoutActivity` 钉三件事——`priceCents=343`、`activityPriceCents=243`、差额=100；整类 **8 tests / 0 failures**（surefire 报告实取）。
   - 🔴 **运行时复验未做**：dev 容器仍是改动前的 jar，mp 真端看到的仍是旧价。按「不谎报已上线」的规矩记为待复验（重建 trade 镜像后重跑 `.tmp/r9-consumer-walk.cjs` 即可，见下）。
2. **记账侧（待拍板，未动）**：`cabinet_order.member_discount_cents` **全仓没有任何写入者**（`grep setMemberDiscountCents` 在 main/java 0 命中），恒 0；而巡检在读它——`DataConsistencyService:1557-1580` 的对账式是 `lineSum − couponDiscount − memberDiscount = payableFromLines`，也就是说这一层一旦补上，**巡检规则与三端明细要同步复核**，不是加一行赋值就完事。
   - 影响面：消费者/商户/运营看到的优惠拆解都缺「会员」这一档（商户分账、按比例退款读的是账面金额，不是推导）。
   - 建议：**单独一批做**，四件套＝结算写入该列（与 `SettlementOrderSupport` 同序）+ 巡检口径复核（PAID_GROSS_MISMATCH / ORDER_AMOUNT 两条规则）+ 三端明细加「会员优惠」行 + 数字实证（非零钉测 + `money-recheck.sql` 四层互证 + 留痕）。值守期不擅动资金字段是本程序的既定边界。

## 批 2 逐页结论（consumer 分包 6 页）

| 页面 | 数据/口径核对（真值来源） | 交互与写面 | 结论 |
|------|---------------------------|------------|------|
| `pages/order-detail/order-detail` 订单详情 | 用真单 `1793498414728440537903`：明细 单价 ¥2.43 ×1、批次 SIM-CB082、货道 A1、商品合计 ¥2.43、优惠券抵扣 减¥0.00、实付 ¥2.43、流水号/扣款时间/柜机齐 | 只读页；「立即退款/提交申诉」入口未点（写面） | **MA-024（P2）待拍板**：同一单在 `result` 页显「活动优惠 减¥1.00」，本页**没有活动行**，且「商品合计」用的是活动后金额 ⇒ 两读面优惠披露不一致 |
| `pages/dispute/detail` 账单审核 | 以 orderId+sessionId 进入得「争议工单不存在」——该页取的是**争议工单 id**（探针传参形态不对，非产品缺陷）；空态文案正确、无 console 错误 | — | 无缺陷（批 3 用真实工单 id 复走） |
| `pages/recharge/recharge` 余额充值 | 当前余额 ¥140.00 = `user_account.balance_cents` 14000；退余额待审 1 条（BR-DEMO-001 ¥5.00，2026-10-07）与 `balance_refund_request` 一致；充值记录 2 条 ¥20 与 `payment_operation` RECHARGE 行一致 | 「体验充值」「申请退余额」为写面，本批只看不点 | 无缺陷。页内「模拟支付(无真实进件)…/体验充值仅用于联调验证」在 `v-else-if="devTools"` 块内（recharge.vue:178-182），**非生产可见**；字符串来自后端 `paymentModeHint`，仓内无硬编码 |
| `pages/balance/balance` 余额明细 | 4 行流水与 `payment_operation` 逐行对：两条 `CHARGE 243 channel=WECHAT balance_before/after=NULL` + 两条 `RECHARGE 2000`（余额 12000→14000） | 只读；下拉刷新/触底加载 | **MA-025（P2）待拍板**：非余额通道的扣款被列进「余额明细」，且缺失的余额快照被兜底成 `¥0.00` 显示 |
| `pages/coupons/coupons` 我的优惠券 | 1 张「新人立减 ¥2」，`user_coupon.status=UNUSED`、`expire_at=2026-10-27`（已过期 5 天）；芯片 全部/未使用/已使用/已过期 | 「设为优先使用」为写面，未点 | 观察项（见下），本轮不领号 |
| `pages/messages/messages` 消息中心 | 芯片 全部 12 / 未读 10 / 订单 7 / 售后 / 优惠券 1 / 充值 2；列表首条与 `payment_operation` 订单号逐一对应；零元单文案「已支付 0 元」与账面一致 | 「开启微信消息提醒」为订阅写面，未点 | 无缺陷（计数与 `notification_log` 同窗口一致，未逐条展开核对 12 条正文） |

## MA-024（P2，待拍板未动）：同一笔订单，结果页披露活动优惠、订单详情页不披露

- 同一单 `1793498414728440537903`：`pages/result/result` 有「活动优惠 减¥1.00 + 本单已享活动价，不再叠加优惠券」（CB-082 本轮加的行）；`pages/order-detail/order-detail` 只有 优惠券抵扣 / 会员优惠 两行（[order-detail.vue:50-57](file:///D:/ai-generated%20code/ai-cabinet/clients/consumer-mp/src/pages/order-detail/order-detail.vue#L50-L57)），**没有活动行**。
- 更深一层：本页「商品合计」取 `originalAmountCents ?? totalAmountCents`，而 `cabinet_order` **没有 `original_amount_cents` 列**（只有 total/paid/member/activity/coupon 五列）⇒ 读模型反推的「原价」= 243（含不回活动那 100），所以就算现在补一行「活动优惠 减¥1.00」，页面会变成「合计 2.43 − 1.00 ≠ 实付 2.43」，**更不自洽**。
- 结论：这不是加一行模板能修的事，与 MA-023② 是同一个根：**订单缺「活动前/会员前毛额」这一档**。建议**与 MA-023② 合成一批做**（毛额语义 + 三端明细 + 巡检对账式复核 + 数字实证），本轮刻意不做半修（会制造新的对不上）。

## MA-025（P2，待拍板未动）：「余额明细」混入非余额通道扣款，并把空余额快照显示成 ¥0.00

- 实测：两条 `CHARGE` 的 `channel=WECHAT`、`balance_before_cents/balance_after_cents` **均为 NULL**（微信支付分先取后付，全程没动余额），但页面把它们列进「余额明细」并显示「购物扣款 −¥2.43 · 余额 ¥0.00」；同页头部「可用余额 ¥140.00」纹丝不动 ⇒ 消费者会读成「余额被扣到 0」。
- 根因两处（都在后端）：
  1. 可见性查询只剔纯冻结/释放（[PaymentOperationMapper.java:32-42](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/mapper/PaymentOperationMapper.java#L32-L42) `HOLD_OPERATION_TYPES`），**不按通道过滤**；
  2. `BalanceLedgerService.toDto` 用 `value()` 把 `null` 兜底成 `0`（[BalanceLedgerService.java:237-244](file:///D:/ai-generated%20code/ai-cabinet/services/trade-service/src/main/java/com/aicabinet/trade/service/BalanceLedgerService.java#L237-L244)），而前端只在 `balanceAfterCents != null` 时渲染 ⇒ 0 永远被渲染。
- 推荐档 A（最小且对症）：可见性查询加「真正动过余额」判据（`balance_after_cents IS NOT NULL` 或 `channel='BALANCE'`），同时 `balanceAfterCents` 改回可空（前端已有 `!= null` 门，天然不再显示假快照）。非余额支付留在「我的订单」。
- 为什么不值守期做：`BalanceTransactionDto` 字段类型变化会牵 shared-types / 三端消费点，且 `value()` 的 null→0 兜底是他处也在用的公共函数——属"改一处要复核三端"的资金读面，按既定边界留给人工批。

## 观察项（批 2，未领号）

- 优惠券：`user_coupon` 唯一一张 `status=UNUSED` 但 `expire_at=2026-10-27` 已过 5 天，仍在「未使用」里且「设为优先使用」可点。置过期靠 `coupon-expire` 日批（`/scheduled-tasks` 显示最近一次执行 **2026-10-24 02:00**，本机夜间关机 ⇒ 批没跑）。**读侧是否按 `expire_at` 实时判过期未钉到源码**（本轮没找到券列表的过滤条件），批 3 或 R10 补钉；若读侧确实只认 status，则应领号（券在页面上「可用」而结算必拒）。

## 批 3 逐页结论（consumer 剩余分包 9 页）

| 页面 | 数据/口径核对（真值来源） | 交互与写面 | 结论 |
|------|---------------------------|------------|------|
| `pages/marketing/index` 热门活动 | 3 张活动卡（含本轮 CB-082 测试活动「活动价核验（测试）10/31–11/08 · 到柜选购」，形态与 SKU 型活动一致）；「我的优惠券 **1 张可用**」 | 「立即领取 / 到柜选购 / 查看券包」为写面与跳转，未点 | **MA-026（P2）**：那张券 `expire_at=2026-10-27` 已过 5 天，读面仍算「可用」 |
| `pages/member/index` 会员中心 | 银卡会员、累计消费 ¥1,284.86、可用积分 124、倍率 1.2、**「结算享 2% 会员折扣」**——与 MA-023 里 350→343 反推出的 2% 互证（同一档折扣两处口径对上）；等级表 4 档门槛 🔴 未与 `member_level_rule` 逐行对（批 4 补） | 只读 | 无缺陷（对账深度不足已标注） |
| `pages/points/points` 积分明细 | 可用 124 = 累计 124 · 已用 0（页内自洽）；「升级还差 376 积分」＝金卡 500 分门槛 − 当前 124；3 条流水（+2/+2/+120，含一条「演示积分补齐」种子）。🔴 未与 `member_points_log` 逐行对（该表按 `member_id` 键、无 user_id 直连列，本轮未追）——留批 4 补 | 只读；「去兑换」跳转 | 无缺陷（对账深度不足已标注） |
| `pages/points/redeem` 积分兑换 | 6 档券与 `coupon_definition` 逐行对（面额/门槛/积分/剩余份数） | 「兑换」为写面，未点 | **MA-028（P3）**：3 张券名与门槛不符（见下） |
| `pages/announcements/announcements` | 「暂无通知公告」= `announcement` **0** 行 | — | 无缺陷 |
| `pages/help/help` 帮助中心 | 客服热线 **400-888-0018** = `system_config.consumer.service_phone`；邮箱/入口齐；FAQ 8 条折叠 | 只读 | **MA-027（P2）**：与协议正文里的电话不是同一个号 |
| `pages/report/report` 故障报修 | 表单四要素（柜机编号/问题类型/补充说明 0/200/联系电话）+ 处理说明 | **写面未提交** | 无缺陷 |
| `pages/feedback/feedback` 意见反馈 | 反馈类型/内容 0/500/联系方式/柜机编号 | **写面未提交** | 无缺陷 |
| `pages/policy/detail` 用户协议 | 版本 V1.2 / 更新日期 2026-08-08；正文含主体信息、《电商法》《价格法》《消保法》《民法典》条款引用；客服电话写作 **4000182992** | 只读 | **MA-027（P2）** |

## MA-026（P2，待拍板未动）：过期券在读面仍算「可用」，选券/用券却按过期拒

- 实测：`user_coupon` 唯一一张 `status=UNUSED`、`expire_at=2026-10-27`（已过 5 天）。消费者端三处仍当它可用——「我的优惠券」列在**未使用**且「设为优先使用」可点；「热门活动」页写「我的优惠券 **1 张可用 · 结算时自动选用最优券抵扣**」。
- 但服务侧判据是另一套：`CouponService.requireValidUserCoupon:283-286` 用到券时若 `expireAt.isBefore(now)` ⇒ 就地翻成 EXPIRED 并抛 409「优惠券已过期」；`evaluateCoupon:477`（自动选最优券）直接把过期券排除。⇒ 「提示有一张可用、结算却一张都不选」是同一张券上的自相矛盾。
- 为什么现在 status 还是 UNUSED：置过期靠 `coupon-expire` 日批（`/scheduled-tasks` 显示最近一次成功 **2026-10-24 02:00**，本机夜间关机 ⇒ 批没跑）。日批缺席时读面无兜底。
- 推荐档 A（最小对症）：读面（列表 + 可用计数 + 可设优先的判据）统一加 `expire_at > now()`，与 `evaluateCoupon` 同判据；日批只作为「把状态落库」的留痕手段，不作为可见性唯一依据。改动集中在查询条件，不动券的资金语义。

## MA-027（P2，待拍板未动）：客服电话两处不一致，协议正文是硬编码

- 帮助中心读配置：`system_config.consumer.service_phone` = **400-888-0018**（`SystemConfigService:834/837` 种子，商户端同值；`PublicLegalServiceTest:14` 钉住 `help.supportPhone()` 必须是它）。
- 用户协议正文硬编码：**4000182992**，出现三处（[policy/detail.vue:70 / :121 / :154](file:///D:/ai-generated%20code/ai-cabinet/clients/consumer-mp/src/pages/policy/detail.vue#L70)）——主体信息、个人信息保护联系方式、行使权利联系方式。隐私政策同页也是它。
- 危害不是「不好看」：协议与隐私政策是**法定联系方式披露**，号码与实际客服号不一致，用户按协议拨打会打不通（或打到别处）。
- 待拍板点：哪个号是真的？我推荐**以配置为准**（帮助中心/商户端/测试断言都用 400-888-0018），把协议正文三处改成读同一配置（模板插值，版本号与更新日期同步递增留痕）；若 4000182992 才是真号，则应改 `consumer.service_phone`/`merchant.service_phone` 两处配置并复核所有引用点。法律文本措辞不由值守会话单方面定，故只登记。

## MA-028（P3，待拍板未动）：3 张积分兑换券的「券名门槛」与实际 `min_spend_cents` 不符

`coupon_definition` 实测（面额/门槛分）：`满 100 减 20 券` = 2000/**5000（¥50）**；`满 50 减 10 券` = 1000/**2000（¥20）**；`满 20 减 5 券` = 500/**0（无门槛）**。兑换页把券名与「满 X 可用」并排显示 ⇒ 同一张卡自相矛盾（用户按名字理解门槛会错）。
- 推荐：以 `min_spend_cents` 为准改券名（结算实际执行的是门槛），并加一条启动/CI 校验：券名里的「满 N」与 `min_spend_cents` 不符即红（同族先例＝字典标签一致性门禁 `DictLabelUniquenessIT`）。
- 属演示种子数据，改动是改名不是删数据；因「名字与门槛谁是权威」是业务口径，值守期只登记。

## 值守期落地记录（2026-11-01 深夜，用户授权「按你的推荐做，继续跑」）

| 条 | 落地内容 | 证据 |
|----|----------|------|
| MA-020 | 后台不再保存口令；勾选改「记住账号」；历史明文在挂载/登录/登出三处无条件删 | `auth-storage.test.ts` 3/0；`vue-tsc` 退出 0 |
| MA-025 | 余额明细可见性加 `balance_after_cents IS NOT NULL` | dev 真数据：排除 2 条微信通道、保留 2 条充值 |
| MA-026 | `countUnusedNotExpired` + `effectiveStatus`（列表/计数/设优先同一判据） | `CouponServiceTest` 26/0 |
| MA-027 | **方向被用户纠正**：4000182992 是实号 ⇒ `V423` 改配置侧 + 三处默认值 + 断言 | dev 事务内试跑命中 2 行后回滚 |
| MA-028 | `V422` 按门槛改 3 张券名 + 写入口校验 `validateNameMatchesThreshold` | 试跑命中 3 行后回滚；测试含「满减券 ¥5」不误伤用例 |
| MA-023②/024 | `V424` 新增 `gross_amount_cents`（DDL+回填）→ 实体/读模型/OpenAPI 属性/shared-types 打通；订单详情按「合计 − 会员 − 活动 − 券 = 实付」摊开，会员档账面为 0 时**反推**（已退款单不反推，防把退款额标成会员优惠） | V424 事务内试跑：`UPDATE 11`、恒等式违例 **0**、样本 gross=343/total=243/activity=100；`SettlementConfirmDisputeTest` 6/0、`OrderPaymentLedgerTest` 3/0、`OrderReadModelOpenApiCustomizerTest` 1/0、consumer `vue-tsc` 0 |
| 巡检拆雷 | `payableFromLines` 去掉重复减会员；过期清理收窄到 `coupon>0` | `DataConsistencyServiceTest` 46/0 |

### MA-029（本轮新领，P1，待拍板）：会员折扣两套机制并存，照抄「补入账」会重复扣减

- 我在本批先按「① 单价里已折」的差额写了 `member_discount_cents`，`SettlementConfirmDisputeTest` 立刻把 500 分差异暴露成 strict-stub 失败：争议重算走的是 `recalculatePayableAfterLineChange`（`SettlementOrderSupport:169-172`），它把该列当**订单级减项再扣一次** ⇒ 若入账，同一笔会员折扣被扣两遍＝**少收钱**。
- 已回退该写入（`SettlementOrderSupport` 现只落毛额，注释写明冲突），并把巡检侧的重复扣减先拆掉（`813bb20e`）。
- 需要定的口径：**A** 单价内折为权威 ⇒ ②的两处订单级减项（争议重算、部分退款比例摊）改为「只展示不再减」；**B** 订单级减项为权威 ⇒ 结算不再把会员价算进单价，行金额恢复目录价。两条都会改变争议/部分退款的金额算法，且都牵动分账 ⇒ 属资金语义人工决策，值守期不硬改。

## 运行时复验（trade 镜像重建后，活库 v424，2026-11-01 23:53+）

Flyway 实跑：`Migrating … 421/422/423/424 → Successfully applied 4 migrations`，`/actuator/health` 200。

| 条 | 复验方式 | 结果 |
|----|----------|------|
| MA-021 | `select perm_code,status from ops_permission where perm_code in (4 个)` | 四行全部 **RETIRED**（含既有 `ops:session:upload` 同形）✅ |
| MA-023① | `GET /api/v2/devices/500909625160/products`（**不敲门**，避免开门副作用） | `priceCents=343`（会员后价）、`activityPriceCents=243` ⇒ 卡面 3.43 − 1.00 = 2.43 自洽 ✅；`member_level_rule` 里 SILVER 2% 与 350→343 对上 |
| MA-024 | automator 打开 `pages/order-detail`（先重烤 `dist/dev/mp-weixin`，产物内已含 `grossAmountCents`/`活动优惠`） | 页面渲染「商品合计 ¥3.43 \| 活动优惠 减¥1.00 \| 实付 ¥2.43」✅（历史单 gross 由回填得来＝会员后价 ⇒ 会员档为 0，符合迁移头写明的人工近似） |
| MA-025 | automator 打开 `pages/balance` | 只剩 2 条「余额充值 +¥20（余额 140/120）」，**微信通道那两行「购物扣款 −¥2.43 · 余额 ¥0.00」已消失** ✅ |
| MA-026 | automator 打开 `pages/coupons` + `pages/marketing` | 券卡打上「已过期」角标（库里 status 仍是 UNUSED ⇒ 证明是读面有效状态在起作用）；活动页文案从「1 张可用」变 **0 张可用** ✅ |
| MA-027 | automator 打开 `pages/help` + SQL | 页面显示 **4000182992**；`consumer/merchant.service_phone` 两行配置同为 4000182992 ✅ |
| MA-028 | 迁移在活库执行（V422 命中 3 行）+ 写入口单测 | 券名已改（试跑阶段 `UPDATE 3`），新增错配由 `validateNameMatchesThreshold` 拦（用例含「满减券 ¥5」不误伤）✅ |

未复验项（诚实记录）：MA-022 只在临时 dev（`:3199`）上挣到 200，**没有在跑的 `:3100` 上复验**——那需要重启别人可能在用的 vite 进程，值守期不动它；MA-020 属前端行为（不再写 localStorage），单测已钉，活浏览器复验留人工。

## 走查副作用（不是缺陷，但必须留痕）





- 「只读」走查里 `reLaunch('/pages/index/index?deviceId=…')` 在**微信端会直接开门**：[index.vue:1100-1103](file:///D:/ai-generated%20code/ai-cabinet/clients/consumer-mp/src/pages/index/index.vue#L1100-L1103) `if (launch.deviceId) { if (launch.autoOpen || !isH5.value) startShoppingFlow(...) }`；而 `autoOpen` 在 [packages/shared-uni/src/qrcode.ts:198-204](file:///D:/ai-generated%20code/ai-cabinet/packages/shared-uni/src/qrcode.ts#L198-L204) 是 `options.autoOpen==='1' || options.open==='1' || !!(deviceId)` ——**只要带柜号就一定为真**，`autoOpen=0` 关不掉。该坑在本页 :805-806 已有注释承认（并刻意让某条链路不带 deviceId 绕行）。
- 隔离实验（`.tmp/r9-autoopen.cjs`，逐步查 `shopping_session` 新行）：①不 mock scanCode、纯首页 ⇒ 新会话 **0**；②只 mock scanCode、首页不带 deviceId ⇒ **0**；③带 `?deviceId=` ⇒ **1**。⇒ 结论：首页不会自己扫码，**带柜号深链即开门**是设计路径（真机需先扫柜门码，不存在误开）。
- 本轮因此在 dev 产生 3 次模拟开门（UTC 03:55:56 / 03:57:19 / 04:03:04，模拟器 `AICABINET_SIM_SHOPPING_MS=20000` 自动关门）：前两条落 `DISPUTED`（重力上报与账面不符，属既有争议口径），末条为走查最后一步留下的会话、已随 20s 定时收口。**未删任何业务数据**，「有疑问」计数里的新增即来源于此。
- 探针自身的取证缺口（记给后续批次）：截图按 `page.path` 落盘，**同路由二次访问互相覆盖** ⇒ `evidence/R09/pages_index_index.png` 留下的是落地页而非购物态；本轮价格结论只建立在文本快照 + SQL 上。批 2 起探针按「路由 + 序号」命名，购物态必须留图。
- 另：`.gitignore:103` 有 `*verify*.png` 规则，`pages/verify/verify` 的截图**天然不入库**（该页结论以文本快照为证）；后续批次命名时避开 `verify` 子串。

## MA-029 方案 A 落地（2026-11-02 值守批，用户已批准「①为权威」）

四步按交接书 §1 落齐，**同一批提交**。落地时把 ② 的算术全仓扫了一遍，实际是 **5 处**而不是交接书写的 2 处（少一处就会造出「预估≠写侧」或「巡检永久误报」）：

| # | 站点 | 改前（②） | 改后（①） |
|---|------|-----------|-----------|
| 1 | `SettlementOrderSupport.recalculatePayableAfterLineChange` | `total = 行合计 − 券 − 会员` | `total = 行合计 − 券`；会员档只夹取留档 |
| 2 | `SettlementOrderSupport.applyProportionalRefundPayable` | `total = 剩余行合计 − 券 − 比例会员` | `total = 剩余行合计 − 券`；会员档仍按比例缩减**只为留档** |
| 3 | `SettlementPartialRefundService.applyPartialRefundPayablePreview`（只读预估腿，交接书未列） | 同 ② | 与写侧同式（铁律 51：预估≠写侧＝资损，2026-10-15 已踩过） |
| 4 | `DataConsistencyService.checkOrderConsistency` 检测 SQL 的 `payable_from_lines` 与 `HAVING`（交接书未列） | `line − coupon − member` | `line − coupon`；`member_discount` 仍在 SELECT/GROUP BY 里供消息打印 |
| 5 | `DataConsistencyService.clearStaleCouponFields`（交接书未列） | 顺手把 `member_discount_cents = 0` 一起抹 | 只清券；会员是合法留档，抹了等于毁 `gross − member − activity − coupon = total` 的一环 |

第 4 处特别说明：`813bb20e` 当时只拆了**修复侧**那条雷（`loadOrderAmountSnapshot`），检测式这条还留着 ⇒ 入账一开就会把**每一单会员折扣**都报成 ORDER_AMOUNT 不一致，且因 `member>0` 落进「多行明细与头金额不一致，需人工」这条修不掉的分支。

入账侧（步骤 2）：`applyItemsToOrder` 现写 `memberDiscountTotal = Σ(目录价 − 会员价) × 数量`，与 V424 的 `gross_amount_cents` 一起让恒等式在**每一单**成立；会员档用 `Math.max(0, …)` 夹住（防止上游实现给出「会员价 > 目录价」把留档写成负数）。

历史数据（步骤 4）：不回填真实业务单金额（`member` 历史恒 0 ⇒ 去掉减法是恒等变换）。**活库实测坐实**：
`select count(*) from cabinet_order where coalesce(member_discount_cents,0) > 0` ⇒ `aicabinet` 与 `aicabinet_openapi_1024` 两个库都 **0 行**。
唯一例外是 V252 的 UAT/dev 演示种子（`unit=line=500`、`member=59`、`total=441`）——它是 ② 形，在新检测式下会永久误报 ⇒ 补 **V425**（`MIGRATION_KIND: backfill`）把**明细行**改成 ① 形（`unit=line=441`，订单头四列不动，V424 已把 gross 反推成 500）；守卫取 V252 完整指纹 + `line_amount_cents = 500` ⇒ 幂等、dev 现网 0 行命中。

测试（步骤 3）：
- `SettlementConfirmDisputeTest.confirmDisputedItems_withMemberDiscount_preservesDiscountOnRecalc` →
  改名 **`confirmDisputedItems_memberDiscountAppliedOnceNotTwice`**，按 ① 真形重写（目录 400 → 会员 392，3 件 ⇒ 1176；旧断言 1100 是 ② 的假想语义，② 落到 ① 数据上会得到 1152＝少收 24 分）。
- 🔴 交接书预警的两条连带 fixture（`withCoupon_…` 期望 800 得 784、`increase_balanceInsufficient_…` 异常类型变化）**没有挂**：这两条的 fixture 里 `member_discount_cents` 恒 0，`recalculate` 的会员夹取本就是 no-op ⇒ 无需改动，6/6 全绿。反证了「上一轮的 784 来自另一种实现」，本批实现更收敛。
- 新增 `SettlementMemberDiscountOnceTest`（6 例，含要求的**反例钉测**：会员 + 活动同时命中 ⇒ `gross 1200 − member 24 − activity 150 = total 1026`，② 会给 1002）+ `SettlementPartialRefundTest.partialRefund_withBookedMemberDiscount_estimateMatchesWriteSide`（预估 392 == 写侧 392，② 会多退 5 分）。
- 指定验证命令 `mvn -pl services/trade-service -am -Dtest='Settlement*Test,Dispute*Test,DataConsistencyServiceTest,OrderPaymentLedgerTest'`：**20 个测试类 0 fail 0 error**（新类 surefire `tests="0"` 是 @Nested 老少报，按 XML `<testcase>` 实取 6）。

数字实证四层（资金改动铁律）：
1. 非零钉测：上表新单测全部用非零会员额（7/16/24 分）断言，0 额一律红。
2. 活库真单：新增 `MemberDiscountLedgerSqlIT`（Testcontainers 真 Postgres，Flyway **V1→V425 从零**，真 `MemberService` + 真 SILVER 2% 种子 + 真 `applyItemsToOrder` + 真 mapper 落库）⇒ 350×3 单读回 `gross 1050 / member 21 / activity 0 / coupon 0 / total 1029`，并在同一库跑独立对账 SQL：**恒等式违规 0 行**、**会员档 > 0 却被折后式判不一致 0 行**、**V252 ② 形残留 0 行**；`Tests run: 2, Failures: 0, Errors: 0`（failsafe 报告 `<testcase>` = 2）。IT 里先断言 `会员单价 < 目录单价` 才谈恒等式——否则 ①/② 在数字上不可区分，属假绿。
3. 活库（dev v424）只读对账：恒等式违规 **0**；新折后式在全量 11 单上误报 **0**；`member>0` 历史行 **0**。
4. 展示层互证：`member_discount_cents` 入账后，consumer `pages/order-detail`（MA-024 已按 `gross` 摊开、账面优先）自然平；`pages/result` 原以 `originalAmountCents`（会员折后行合计）为基准再列「会员优惠」行 ⇒ 入账后会显示「3.43 − 0.07 − 1.00 ≠ 实付 2.43」这种用户自己算不平的行 ⇒ 本批把基准改成 `grossAmountCents`（缺失才退回 `originalAmountCents`），与 order-detail 同口径。

运行时腿（诚实标注）：**活栈 HTTP 会员单未验** —— `:18080` 现跑镜像是 `f9905080`（不含本批），要挣这条得重建共享容器；本批按值守硬约束「脏树不动共享容器」停在提交前，把这条留给 R11 全链路（那一轮本来就要用新镜像跑一遍会员单结算）。IT 已覆盖「真库 + 真生产写入口 + 真 SQL」，缺的只是 HTTP/门/识别那半条链。

## M 批 1（merchant-mp 主页 5 页 + 设备详情，2026-11-02 值守）

**环境与锚点**：锚 HEAD `1df3d484`（MA-029 已落地并推）；商户端 IDE 用 `cli.bat auto --project clients/merchant-mp/dist/dev/mp-weixin --auto-port 9420` 新起实例（consumer 的 `:9421` 实例没动）；产物先按固定串比对确认不是旧码（`replace(/\D/g` 0 命中、`[-_][A-Z]*\d` 1 命中 ⇒ MA-006 那次改动在产物里），改完 MA-030 后重烤 `build:mp-weixin:dev` 并再次 `auto`。
登录走 HTTP `/api/v2/auth/merchant-password-login`（账号 13800138001）+ `setStorageSync('merchant_token'|'merchant_user_id')` 注入（合成输入事件在 0.13.0 仍坏，与配方一致）。探针 `.tmp/r9-merchant-walk.cjs`、类型取证 `.tmp/r9-todo-types.cjs`。

🔴 **零副作用实证**：走查后 `select ... from shopping_session where created_at > now() - interval '3 hours'` = **0 行**（与 consumer 批「带柜号深链即开门」不同，商户端主页 reLaunch 不触发开门）。写面本轮一个都没提交。

### 逐页结论

| # | 页面 | 渲染 | 结论 |
|---|------|------|------|
| m1 | `pages/login/login` | 8 节点 | 只有「手机号/密码」两输入 + 副标题；无「记住密码」类残留（MA-020 只涉 admin，此处本就不存口令）✅ |
| m2 | `pages/home/home` | 52 节点 | KPI 与 workbench 逐字段对得上（24 争议 / 8 低库存 / 1 临期 / 0 退款 = `/api/v2/merchant/workbench` 的 openDisputes/lowStockItems/expiryAlerts/refundOrders）；🔴 **MA-030**：快捷入口角标 60 ≠ 待办页四卡合计 58 |
| m3 | `pages/devices/devices` | 41 节点 | chips 全部 3 / 在线 1 / 离线 2 / 停售 1 与 API 三台柜状态一致；卡片同时给「今日收入 ¥0.00」「填充率」「最近补货/核对」时间戳，未发现口径打架 |
| m4 | `pages/alerts/alerts` | 371 节点 | 四卡合计 58（48/4/5/1）⇒ 修后 60（48/4/**7**/1）；🔴 **MA-031**：`补货任务超时`、`开门超时` 两行的类型标签渲染成「未知」；观察项：厂商原文直接进卡片明细「设备 uploadOrderError errorMsg：开门时间过长…」 |
| m5 | `pages/mine/mine` | 59 节点 | 入口清单与 35 页路由一致（无幽灵入口）；客服热线 4000182992 与 MA-027 拍板的实号同源 ✅；组织名「默认直营商户、演示商户B」+「点击切换可见组织」= CB-079 档 A 落地形态 |
| m6 | `pages/device-detail/device-detail` | 99 节点 | 开门柜（将邑）显示「在架商品 / 开门柜按商品备货，无货道概念」= 10-30 拍板口径 ✅；在架 3 行与 10-31 台账 6→3 一致；采集学习进度可读 |

### MA-030（P2，轮内已修）细节

- 根因：`useHomeWorkbench.ts` 里同一函数把首页角标算成 `mergedTodos.length`（全量），tab 角标算成 `countTodoCategories()` 合计（归类）；而归类表缺 `RESTOCK_TASK_TIMEOUT`（活体 2 行，来自 `merchant/exceptions?status=OPEN`）⇒ 60 vs 58。
- 取证：`.tmp/r9-todo-types.cjs` 把四源类型全列了一遍（24 ACTION:DISPUTE / 23 EXCEPTION:RECOGNITION_FAILED / 8 ACTION:LOW_STOCK / **2 EXCEPTION:RESTOCK_TASK_TIMEOUT** / …），差额精确等于 2 ⇒ 不是猜测。
- 修法：沿用 2026-10-27 `OPEN_TIMEOUT` 归卡的同形解法——`RESTOCK_TASK_TIMEOUT` 进 `TODO_CATEGORY_STOCK`；再新增**单源** `todoBadgeCount()`，首页与 alerts 两处徽标都改走它（漂移由构造消除，而不只靠类型表补全）。
- 证据四层：① `todo-list.test.ts` 新增 3 例，含「全类型清单 ⇒ 徽标==列表行数」不变式；**先红再绿**实证（临时摘掉归卡 ⇒ 2 failed，恢复 ⇒ 29 passed）；② `vue-tsc --noEmit` 退出 0；③ 重烤产物 + 活体复验：home 角标 60、alerts 四卡 48/4/7/1 = 60，两数同屏一致；④ 图证 `evidence/R09/b1-02_pages_home_home_pre-fix.png` / `b1-04_pages_alerts_alerts_pre-fix.png`（修前）与 `b1-07`/`b1-08`（修后）。
- 取证缺口（诚实记）：修前 alerts 的截图与修后截图由同一次探针的两轮跑出，文件名靠 `_pre-fix` / `_post-fix` 区分；`m0x` 序号是按「本次运行」计数、重跑会覆盖，**后续批次探针必须带 `START_INDEX`**（已记进下方配方欠账）。

### 观察项（未领号，留给轮末文案批 / R10）

1. 首页「库存偏低 8」（`workbench.lowStockItems`，分母=**商品数**）与待办页「库存卡 7」（分母=**待办条目数**）不是同一个量纲。两个数都合法，但同名不同物、又同屏出现，R10「同一端点三端口径」需要点名是否要在文案上区分（如「N 种商品」vs「N 条待办」）。
2. 待办卡片明细里出现厂商原文 `设备 uploadOrderError errorMsg：开门时间过长，会话已 fail-closed 转人工审核` ⇒ 技术黑话外泄给商户（`merchantAlertTitle` 只做了「设备→柜机」替换）。属文案层，倾向并入 MA-031 那批一起处理（同一族：读面把内部码/原文直送用户）。
3. `alertTypeLabel` 对未知类型返回「未知」而不是回退到标题，导致行首「未知 | 补货任务超时」自我矛盾（标题明明是可读的）——已并入 MA-031 的修法判据。

### 商户端走查配方补丁（给 M 批 2+）

- `cli.bat auto` 可以在同一台机同时跑 consumer(:9421) 与 merchant(:9420) 两个实例，本批实证互不影响。
- 探针从**仓库根**跑（`node .tmp/r9-merchant-walk.cjs`）——脚本里截图路径是相对路径，在 `.tmp/` 下执行会写到 `.tmp/docs/…` 造成「以为没出图」。
- 命名 `b<批>-<序号>_<路由>.png`，序号必须**批内全局唯一**且不复用；写面页只开确认框、不点确定。

## M 批 2（merchant-mp 履约写面 6 页：定价/补货任务/自主补货/仓库盘点/柜机盘点/理货记录，2026-11-02）

**探针方法升级（影响全部后续批次读数）**：v1 只采 `text` 节点 ⇒ **直接写在 `view` 里的文案全漏**（本页族实测漏掉 self-replenish 的空态句、pricing 的柜机选择条、tally 的两 tab 计数），差点把「空态」误报成「明细没渲染」。v2 同时采 `text` + `view`，并按精确重复/超长拼接过滤。改名后的图：`b2-m1x*`＝v1 读数（修前），`b2-m2x*`＝v2 读数（修后）。

**写面纪律**：6 页全部只看渲染，一个「提交/过账/开门」按钮都没点；`发起补货（0 种）`、`提交实盘调账`、`完成并过账` 都只读到 label。走查后 `shopping_session` 新增 0 行（同批 1 复验）。

### 逐页结论

| # | 页面 | v2 读数 | 结论 |
|---|------|---------|------|
| m21 | `pages/self-replenish` | 14 节点 | 🔴 **MA-033（已修）**：空明细时同时显示「已按缺货与销量建议预填数量」与「该柜机暂无在架商品」两句打脸话。根因链已查实：默认选中 门店二号柜，`/merchant/replenishment/suggestions?deviceId=CAB-OTHER` 返回 `[]`（该柜 7 个货道 `assignedSkuId` 全 null），而提示行是无条件渲染 ⇒ 加 `v-if="draftLines.length"`；修后活体复验只剩一句实话 + 按钮「发起补货（0 种）」 |
| m22 | `pages/pricing` | 31 节点 | 4 行覆盖价（雪碧×2 柜、加多宝、康师傅）基准/可改区间/当前价三数自洽；「柜机：全部柜机 + 调价历史」入口在位。v1 看到的「同一句出现两遍」经核对是**探针父子节点重复**（`text` 外层含内层），不是产品缺陷 ⇒ 不领号 |
| m23 | `pages/warehouse-stocktake` | 18 节点 | 🔴 **MA-032（open）**：待录入单显示 `明盘 · 账面 502 · 实盘 502 · 差异 0（0 行）`。DB 实证 `PD20261028-001` = `DRAFT/OPEN`、9 行全 `PENDING`、`counted_qty` 创建时按账面预填 ⇒ 数字忠实于数据，但「差异 0（0 行）」的语义会被读成「已核平」；建议 DRAFT 态显示「实盘 未录入（默认同账面）」（`warehouse-stocktake.vue:43-44` 的 `?? '—'` 只兜 null，兜不到「预填等于账面」） |
| m24 | `pages/tally-records` | 10 节点 | 「盘点（…）」看着像加载卡住 ⇒ **查证后不领号**：`tally-records.vue:65-92` 的 `load()` 只拉当前 tab（默认 restock），另一侧 `stocktakeLoaded` 本就 false ⇒ `'…'` 是「未加载」的既定 affordance（lazy-by-design）。空态文案「近 30 天没有配货记录」诚实 ✅ |
| m25 | `pages/device-stocktake` | 25 节点 | 开门柜实盘 3 行（雪碧 4 / 冰红茶 1 / 加多宝 1）与设备详情、真柜台账一致 ✅；🟡 观察项（未领号）：文案「开门柜：**已按**门禁式开门，清点后按商品提交实盘」是过去式，而本次是 `?deviceId=` 深链进入、没有扫码也没有开门 ⇒ 两种读法（①陈述流程设计 ②声称已开门）。留轮末/ R10 文案批定夺，判据 = 未开门时不得用完成时态 |
| m26 | `pages/replenishment` | 86 节点 | KPI「2 待处理 / 2 已完成 / 今日完成率 0%」与任务列表（22 待处理、21 进行中、17/8 已完成）不矛盾——已完成两条是 10-30/11-01 而非今日；「缺货巡柜」显示 演示智能柜 6 项缺货·缺口 36 件、将邑 2 项·缺口 6 件，与 `/shortage-summary` 口径一致（CB-076 单源） |

### 本轮取证脚本

`.tmp/r9-merchant-walk.cjs`（v2 文本采集 + `START_INDEX`）、`.tmp/r9-merchant-api-probe.cjs`（PATHS 批量 GET）、`.tmp/r9-todo-types.cjs`（四源类型分布）、`.tmp/r9-self-replenish-probe.cjs` / `-data.cjs` / `-pick.cjs`（picker 实验）。
🔴 新坑记录：① `page.data()` 在 uni-app Vue3 编译产物里是**混淆键**（`eO,uR,a,b…`），读不到业务状态 ⇒ 判状态只能靠 DOM 文本 + 后端接口对账；② 原生 `<picker>` 的选项层 automator 采不到，`page.callMethod('onDevicePick')` 报 `not exists`（setup 函数不挂到 page 实例）⇒ 「换柜机」这条交互无法脚本化，只能人工判据留给 R10。

## M 批 3（merchant-mp 交易/消息面 6 页：orders / order-detail / disputes / video / messages / notify，2026-11-02）

锚 HEAD `e054c02f`；探针 v2；写面零点击（notify 只读渲染，没点「保存/开启提醒」）；走查后 `shopping_session` 新增 0 行。
🔴 **本批 0 条新领号**——三个「看着像缺陷」的现象全部查证到「不是产品缺陷」，逐条留证据（防后续会话重复调查）：

| 现象（快照原话） | 查证 | 结论 |
|------------------|------|------|
| 消息中心出现「商户名称：产文祥 结算周期：2026年10月13日」，而当前账号是 默认直营商户 ⇒ 疑似**跨商户消息泄漏（本可判 P1）** | `notification_log` id=11 `merchant_id=MCH-DEFAULT`、body 写 产文祥；`merchant` 表现只有 MCH-DEFAULT=默认直营商户 / MCH-OTHER=演示商户B；取数代码 `MerchantDailyIncomeNotifyScheduler:156-158` = `merchantRepository.findById().map(getMerchantName).orElse(id)` ⇒ 正文是**发送时的名称快照**，该商户后来被改名；同期最新一条 id=42（11-02）正文已是「默认直营商户」✅ | **不领号**：不是收件人错，是历史快照。R11 若要判「通知正文跟随改名」再单独立项 |
| 消息「任务已分配…涉及 **719325384517**。」显示裸设备号 | `select count(*) from device_info where device_id='719325384517'` = **0** ⇒ 该柜机已被前几轮演示清理删掉；正文同样是发送时取不到名称的回退 | **观察项**（非产品缺陷：数据是历史清理的后遗症）。若要改，方向是「设备已删除」而不是裸号 |
| 订单详情「流水号 1793498414728440537903」与「订单号」一模一样 ⇒ 疑似把订单号当流水号（MA-006 同族） | DB：该单 `pay_trade_no = MOCK-PS-1793498414728440537903`、`payment_operation_id` 空；模板 `order-detail.vue:75-79` 有 `v-if="payTradeNo \|\| paymentOperationId"` 守卫并走 `displayBizNo` ⇒ 剥掉 `MOCK-PS-` 前缀后剩下的数字段本来就等于订单号 | **不领号**：dev mock 流水的号码形态所致；真微信支付流水号不同源不会撞。留 R11 用真流水复验 |

### 逐页结论（无缺陷也要留痕）

- `pages/orders`：状态 chips（全部/已支付/已退款/争议中/已取消）+ 时间 chips（全部时间/今天/近7天/近30天）+ **零元单**开关在位（10-22 拍板的「隐藏零元单」口径已改成显式开关）；「导出当前柜机的全部订单（状态/时间筛选不适用于导出）」是**主动交代导出与筛选不一致**的诚实文案 ✅；券减单显示「优惠减¥2.00 / ¥1.50 / 原价 ¥3.50」三数自减得平（1.50 = 3.50 − 2.00）✅；开门柜行不带货道、货道柜行带「货道A1」与 10-28 拍板一致 ✅。
- `pages/order-detail`：已扣 ¥2.43 / 实收 ¥2.43 两数取自支付流水（`OrderPaymentLedger`）；批次 `SIM-CB082` 显示为模拟柜上报的模拟批次，与 dev mock 一致；分账状态「仅记账」= `LEDGER_ONLY`（0 抽成档）✅；「查看柜机 / 查看购物视频 / 相关争议」三个跳转入口齐。
- `pages/disputes`：三 tab（待审核/已结案/已关闭）+ SLA（剩余 34 小时 / 已超时）+「有录像」标记 + 工单号原样展示（MA-006 修复形态）✅。
- `pages/video`：空态文案「本单没有可播放的录像。超时免单、模拟柜或文件未上传时常见。」= 把 10-31 查到的「正常单视频不上 OSS 是固件策略」翻译成了人话 ✅，且给「重新加载 / 返回订单详情」两条出路。
- `pages/messages`：8 类 chips + 未读 15；日结收入消息带「关联单号：20261101」；补货消息带「关联单号：22/21/19…」可回溯任务 ✅。
- `pages/notify`：微信绑定态「未绑定」+ 8 类提醒勾选 + 保存；未绑定态仍展示类型清单（点了保存会怎样未验——写面纪律，留给 R11 或人工）。

## 待拍板 / 待办

1. ~~**MA-023② + MA-024 合成一批**~~ ✅ 已落地（毛额列 V424 + 巡检口径 + 三端明细行 + **MA-029 方案 A 入账**，2026-11-02 见上节）：结算写清毛额与各档优惠、巡检 `ORDER_AMOUNT` 折后式改为「行合计 − 券」、consumer 结果页/详情页按 `合计−会员−活动−券=实付` 摊开、数字实证＝非零钉测 + `MemberDiscountLedgerSqlIT` 活库真单 + dev 只读对账 + 展示层互证。
3. ~~**MA-025**~~ 见下方第 7 条（已落地）。
2. ~~MA-020（R8，P1）~~ ✅ 已落地（档 B）：`LoginView` 不再预填/写入口令，勾选项改名「记住账号」，`admin_password` 与开关键在**挂载、登录、登出**三处无条件删除；`auth-storage.test.ts` 3 例钉住「勾选着也删」「手机号照旧保留」「取消勾选照旧清手机号」。
4. ~~**MA-026**~~ ✅ 已落地（读面判据统一到 `expire_at`）：`UserCouponMapper.countUnusedNotExpired` + `CouponService.effectiveStatus`（列表按有效状态筛、下发有效状态）⇒ 「未使用」页签、「N 张可用」计数、「设为优先使用」三处同时收口；`CouponServiceTest` 26/0。
5. ~~**MA-027**~~ ✅ 已落地，**方向被用户当场纠正**：我原本推荐「以配置为准」，用户 2026-11-01 确认 **4000182992 才是实号**（`docs/figures/payscore-form-fill-pack.md` §55 记它已通过微信支付客服电话认证并展示在账单详情页；`public-legal.json` 协议正文三处也是它）⇒ 改成**动配置侧、不动法律文本**：`V423` 把 `consumer/merchant.service_phone` 从 V55 占位号 400-888-0018 改到实号（守卫：只改仍等于占位号的行），同步 `SystemConfigService.ensureDefaults`、`ops/feature-flags.json` 两处 default、`public-legal.json` 的 `help.supportPhone`、`PublicLegalServiceTest` 断言。
6. ~~**MA-028**~~ ✅ 已落地：`V422` 按门槛改 3 张券名（满 50 减 20 / 满 20 减 10 / 无门槛减 5），并加写入口校验 `CouponService.validateNameMatchesThreshold`（只认「满 N 减」这种带数字的承诺，「满减券 ¥5」不误伤；「无门槛」+门槛>0 也拒）。🔴 查证补充：这三张券是 V163 演示数据，**V345 已在 prod/staging（`seed_env='none'`）整行删除** ⇒ V422 在生产命中 0 行，真正长期有效的是写入口校验。
7. **MA-025** ✅ 已落地（读面判据）：`findVisibleByUserIdOrderByCreatedAtDesc` 加 `balance_after_cents IS NOT NULL`（判据取「有没有余额快照」而不是 channel，将来「微信单退到余额」仍会出现）。dev 真数据验证：改后 余额明细 命中 2 条 RECHARGE、排除 2 条 WECHAT CHARGE（正是页面上「购物扣款 −¥2.43 · 余额 ¥0.00」那两行）。🔴 留观察：`BalanceLedgerService.toDto` 的 `value()` null→0 兜底仍在（现不可达），`BalanceTransactionDto` 两个余额字段仍是 `int`——改可空要牵三端与 openapi，未在本批做。
8. 批 4 清单：**merchant-mp 35 页**（登录/首页/设备/告警/我的 5 主页 + 30 分包）；另补 consumer `announcements/detail`（需先有一条公告）、`member_level_rule` 与积分流水的逐行对账、`dispute/detail` 用真实工单 id 复走。
9. **MA-031（P3，open）**：`merchant_alert_type` 基线缺 4 个待办类型 ⇒ 行首显示「未知」；轮末文案批一次做齐（基线 + dist 重烤 + `SysDictBootstrap` + DB 字典迁移），判据 = 待办页 0 行「未知」。同批可并：待办卡片把厂商原文 `设备 uploadOrderError errorMsg：…` 直送商户（M 批 1 观察项 #2）。
10. **MA-032（P3，open）**：仓库盘点「待录入」单显示 `实盘 = 账面 · 差异 0（0 行）` 的语义问题——先判「明盘默认=账面」是否本就是拍板设计（`备货单/盘点` 有「只提交与台账有差异的商品」的既有口径），再决定改文案还是改预填；不建议顺手把 counted 置 0（会让明盘失去默认值便利）。
11. **M 批 4+ 待走**（还剩 17 页）：批 4 财务 6 页 `settlements, bills, splits, wallet, line-wallet, business`；批 5 增长/报表 6 页 `tax, cabinet-reports, ops-config, purchase, announcements, announcements/detail`；批 6 账号与收尾 5 页 `team, password-change, policy, prep-generate, prep-list`。
    🔴 财务批的既有判据（别重新发明）：`money-recheck.sql` 7a/7c 已随 MA-029 扩段；0 抽成档＝`LEDGER_ONLY`（PROJECT_KNOWLEDGE §6.2）；提现费 6‰ 不封顶可按商户覆盖（10-16 拍板）；商户钱包与线长钱包是两个不同域（`line-wallet` ≠ 分账）。
