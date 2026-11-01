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

## 走查副作用（不是缺陷，但必须留痕）



- 「只读」走查里 `reLaunch('/pages/index/index?deviceId=…')` 在**微信端会直接开门**：[index.vue:1100-1103](file:///D:/ai-generated%20code/ai-cabinet/clients/consumer-mp/src/pages/index/index.vue#L1100-L1103) `if (launch.deviceId) { if (launch.autoOpen || !isH5.value) startShoppingFlow(...) }`；而 `autoOpen` 在 [packages/shared-uni/src/qrcode.ts:198-204](file:///D:/ai-generated%20code/ai-cabinet/packages/shared-uni/src/qrcode.ts#L198-L204) 是 `options.autoOpen==='1' || options.open==='1' || !!(deviceId)` ——**只要带柜号就一定为真**，`autoOpen=0` 关不掉。该坑在本页 :805-806 已有注释承认（并刻意让某条链路不带 deviceId 绕行）。
- 隔离实验（`.tmp/r9-autoopen.cjs`，逐步查 `shopping_session` 新行）：①不 mock scanCode、纯首页 ⇒ 新会话 **0**；②只 mock scanCode、首页不带 deviceId ⇒ **0**；③带 `?deviceId=` ⇒ **1**。⇒ 结论：首页不会自己扫码，**带柜号深链即开门**是设计路径（真机需先扫柜门码，不存在误开）。
- 本轮因此在 dev 产生 3 次模拟开门（UTC 03:55:56 / 03:57:19 / 04:03:04，模拟器 `AICABINET_SIM_SHOPPING_MS=20000` 自动关门）：前两条落 `DISPUTED`（重力上报与账面不符，属既有争议口径），末条为走查最后一步留下的会话、已随 20s 定时收口。**未删任何业务数据**，「有疑问」计数里的新增即来源于此。
- 探针自身的取证缺口（记给后续批次）：截图按 `page.path` 落盘，**同路由二次访问互相覆盖** ⇒ `evidence/R09/pages_index_index.png` 留下的是落地页而非购物态；本轮价格结论只建立在文本快照 + SQL 上。批 2 起探针按「路由 + 序号」命名，购物态必须留图。
- 另：`.gitignore:103` 有 `*verify*.png` 规则，`pages/verify/verify` 的截图**天然不入库**（该页结论以文本快照为证）；后续批次命名时避开 `verify` 子串。

## 待拍板 / 待办

1. **MA-023② + MA-024 合成一批**：订单缺「会员前/活动前毛额」这一档（`member_discount_cents` 无写入者、`original_amount_cents` 无列）⇒ 三端优惠拆解与巡检对账式都少一层。四件套＝结算写清毛额与各档优惠 + 巡检 `PAID_GROSS_MISMATCH/ORDER_AMOUNT` 口径复核 + 三端明细行（含 order-detail 活动行）+ 数字实证（非零钉测 + `money-recheck.sql` 四层互证 + 留痕）。
3. ~~**MA-025**~~ 见下方第 7 条（已落地）。
2. ~~MA-020（R8，P1）~~ ✅ 已落地（档 B）：`LoginView` 不再预填/写入口令，勾选项改名「记住账号」，`admin_password` 与开关键在**挂载、登录、登出**三处无条件删除；`auth-storage.test.ts` 3 例钉住「勾选着也删」「手机号照旧保留」「取消勾选照旧清手机号」。
4. ~~**MA-026**~~ ✅ 已落地（读面判据统一到 `expire_at`）：`UserCouponMapper.countUnusedNotExpired` + `CouponService.effectiveStatus`（列表按有效状态筛、下发有效状态）⇒ 「未使用」页签、「N 张可用」计数、「设为优先使用」三处同时收口；`CouponServiceTest` 26/0。
5. ~~**MA-027**~~ ✅ 已落地，**方向被用户当场纠正**：我原本推荐「以配置为准」，用户 2026-11-01 确认 **4000182992 才是实号**（`docs/figures/payscore-form-fill-pack.md` §55 记它已通过微信支付客服电话认证并展示在账单详情页；`public-legal.json` 协议正文三处也是它）⇒ 改成**动配置侧、不动法律文本**：`V423` 把 `consumer/merchant.service_phone` 从 V55 占位号 400-888-0018 改到实号（守卫：只改仍等于占位号的行），同步 `SystemConfigService.ensureDefaults`、`ops/feature-flags.json` 两处 default、`public-legal.json` 的 `help.supportPhone`、`PublicLegalServiceTest` 断言。
6. ~~**MA-028**~~ ✅ 已落地：`V422` 按门槛改 3 张券名（满 50 减 20 / 满 20 减 10 / 无门槛减 5），并加写入口校验 `CouponService.validateNameMatchesThreshold`（只认「满 N 减」这种带数字的承诺，「满减券 ¥5」不误伤；「无门槛」+门槛>0 也拒）。🔴 查证补充：这三张券是 V163 演示数据，**V345 已在 prod/staging（`seed_env='none'`）整行删除** ⇒ V422 在生产命中 0 行，真正长期有效的是写入口校验。
7. **MA-025** ✅ 已落地（读面判据）：`findVisibleByUserIdOrderByCreatedAtDesc` 加 `balance_after_cents IS NOT NULL`（判据取「有没有余额快照」而不是 channel，将来「微信单退到余额」仍会出现）。dev 真数据验证：改后 余额明细 命中 2 条 RECHARGE、排除 2 条 WECHAT CHARGE（正是页面上「购物扣款 −¥2.43 · 余额 ¥0.00」那两行）。🔴 留观察：`BalanceLedgerService.toDto` 的 `value()` null→0 兜底仍在（现不可达），`BalanceTransactionDto` 两个余额字段仍是 `int`——改可空要牵三端与 openapi，未在本批做。
8. 批 4 清单：**merchant-mp 35 页**（登录/首页/设备/告警/我的 5 主页 + 30 分包）；另补 consumer `announcements/detail`（需先有一条公告）、`member_level_rule` 与积分流水的逐行对账、`dispute/detail` 用真实工单 id 复走。
