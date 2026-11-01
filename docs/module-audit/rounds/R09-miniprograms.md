# R9 双小程序（consumer-mp 23 页 / merchant-mp 35 页）

- **锚**：发现锚 `55bac20f`（R8 批 2 收口提交）；修复锚见本批末尾提交号。
- **环境**：本机 dev 栈；**微信开发者工具 + miniprogram-automator**（`ws://127.0.0.1:9421`，consumer 项目 `dist/dev/mp-weixin`），判据按用户 2026-10-27 的口径——只看 mp 真端，H5 红不阻塞。
- **登录注入**：合成输入在 automator 上坏（记忆 `mp-automator-verify`），走 HTTP 短信登录（dev 验证码直读 `sms_verification_code`）+ `setStorageSync` 注 `consumer_token/consumer_user_id/consumer_token_expires/consumer_server_boot`，`mockWxMethod('scanCode')` 提供柜号。
- **进度**：批 1 ✅（consumer 主包 7 页）；批 2（consumer 交易履约分包 6 页）、批 3+（增长/账户分包、merchant 35 页）未开始。

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

## 走查副作用（不是缺陷，但必须留痕）

- 「只读」走查里 `reLaunch('/pages/index/index?deviceId=…')` 在**微信端会直接开门**：[index.vue:1100-1103](file:///D:/ai-generated%20code/ai-cabinet/clients/consumer-mp/src/pages/index/index.vue#L1100-L1103) `if (launch.deviceId) { if (launch.autoOpen || !isH5.value) startShoppingFlow(...) }`；而 `autoOpen` 在 [packages/shared-uni/src/qrcode.ts:198-204](file:///D:/ai-generated%20code/ai-cabinet/packages/shared-uni/src/qrcode.ts#L198-L204) 是 `options.autoOpen==='1' || options.open==='1' || !!(deviceId)` ——**只要带柜号就一定为真**，`autoOpen=0` 关不掉。该坑在本页 :805-806 已有注释承认（并刻意让某条链路不带 deviceId 绕行）。
- 隔离实验（`.tmp/r9-autoopen.cjs`，逐步查 `shopping_session` 新行）：①不 mock scanCode、纯首页 ⇒ 新会话 **0**；②只 mock scanCode、首页不带 deviceId ⇒ **0**；③带 `?deviceId=` ⇒ **1**。⇒ 结论：首页不会自己扫码，**带柜号深链即开门**是设计路径（真机需先扫柜门码，不存在误开）。
- 本轮因此在 dev 产生 3 次模拟开门（UTC 03:55:56 / 03:57:19 / 04:03:04，模拟器 `AICABINET_SIM_SHOPPING_MS=20000` 自动关门）：前两条落 `DISPUTED`（重力上报与账面不符，属既有争议口径），末条为走查最后一步留下的会话、已随 20s 定时收口。**未删任何业务数据**，「有疑问」计数里的新增即来源于此。
- 探针自身的取证缺口（记给后续批次）：截图按 `page.path` 落盘，**同路由二次访问互相覆盖** ⇒ `evidence/R09/pages_index_index.png` 留下的是落地页而非购物态；本轮价格结论只建立在文本快照 + SQL 上。批 2 起探针按「路由 + 序号」命名，购物态必须留图。
- 另：`.gitignore:103` 有 `*verify*.png` 规则，`pages/verify/verify` 的截图**天然不入库**（该页结论以文本快照为证）；后续批次命名时避开 `verify` 子串。

## 待拍板 / 待办

1. **MA-023 记账侧**（会员折扣入账 + 巡检口径联动），见上「建议单独一批做」。
2. MA-020（R8，P1）后台口令常驻 localStorage 仍未动——等用户拍三档。
3. 批 2 清单：consumer 交易履约分包 `order-detail / dispute / recharge / balance / result` 已完成部分只补 order-detail、dispute；写面（申请退款、争议提交、充值下单）只开框验确认门。
4. `pages/index/index` 修复后的真端复验：需先重建 trade 镜像（**必须等共享树干净**，否则会把 peer 的脏源码烤进产物）。
