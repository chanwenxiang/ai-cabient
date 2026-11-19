# 支付宝小程序（消费者端）上线清单

> 状态：**代码侧已接通**（2026-11-19，MA-363/364）；**平台侧 1、3 项已完成**（2026-11-20 用户确认）。
> 本文只列「还差什么」，按「谁能做」分组：平台侧（必须你在支付宝后台点）→ 部署侧 → 验收。
>
> 🔴 **当前唯一硬阻塞：小程序支付产品开通不了（商户资质问题）⇒ 支付能力搁置。**
> 影响面：支付宝端**不能充值**。
>
> ⚠️ **余额不与微信账号共享**（2026-11-20 复核 `AuthService.createAlipayConsumer`）：
> 支付宝首次登录按 `alipay_user_id` 建**独立账号**（`phone_number = "ali"+userId`、`balance_cents = 0`），
> 不与同人的微信账号合并 ⇒ 「微信端充过值就能在支付宝端用」**是错的**。
> 而开门前置（`UserValidationService.validateCanOpenDoor`）要求「免密就绪（按渠道）**或** 可用余额 ≥ 预授权金额」，
> 支付宝新号余额 0 且无免密签约 ⇒ **开门直接被 412 拦**（连开门都测不了，不只是不能充值）。
>
> 开通前的唯一可用路径：**运营在后台给支付宝测试账号人工调账**
> （`POST /api/v2/ops/admin/users/{userId}/balance`，body `{deltaCents, idempotencyKey}`，
> 记 `ADMIN_ADJUST` 台账）⇒ 除「充值」外全链路可验。结论：支付产品开通前只做白名单内部试用，不对外放量。

## 一、代码侧已完成（可复核）

| 环节 | 实现 | 复核方式 |
| --- | --- | --- |
| 编译通路 | `@dcloudio/uni-mp-alipay` 同版本平台包 + `manifest.json` 的 `mp-alipay` 节点 | `pnpm --filter @aicabinet/consumer-mp build:mp-alipay:dev` |
| appid 注入 | `scripts/inject-alipay-mp-env.mjs`：release 缺 `MP_ALIPAY_APPID` 直接失败（避免 `touristappid` 包） | 不配变量跑 `build:mp-alipay` 应红 |
| 静默登录 | `my.getAuthCode(scopes=auth_base)` → `POST /api/v2/auth/alipay/login`（服务端 `alipay.system.oauth.token` 换 user_id 建档） | 真机扫码后应直接进首页 |
| 小程序内支付 | `alipay.trade.create`（`buyer_id` + `product_code=JSAPI_PAY`）→ 客户端 `my.tradePay({tradeNO})` → 轮询到账 | 充值 1 元，看钱包余额 |
| 落地页分流 | `/o/{deviceId}`：支付宝 UA → 先小程序承接页（`alipays://`），未配小程序才回落 H5/引导页 | `curl -A AlipayClient https://<域名>/o/<柜机号>` |
| 扫码传参 | `parseLaunchOptions` 同时认微信 `q` 与支付宝 `qrCode`（qrCode 兜底 `channel=ALIPAY`）；首页用 `uni.getLaunchOptionsSync()` 兜底（支付宝码值不进页面 `onLoad`） | `npx vitest run src/utils/launch-options.test.ts` |
| 门禁 | `check:alipay-mp-wiring`（登录/支付/落地页/构建注入 8 类判据 + 15 条负向夹具） | `pnpm check:alipay-mp-wiring` |
| 单测 | `AlipayPayClientMiniTest`（4 条：金额单位/产品码/buyer_id/缺 trade_no fail-closed） | `mvn -pl services/trade-service -am test -Dtest=AlipayPayClientMiniTest` |

## 二、平台侧

> **进度（2026-11-20）**：第 1 项（确认小程序应用与 appid）与第 3 项（关联普通链接二维码）**已完成**。
> 第 2 项（小程序支付）**搁置**（商户资质）。第 4 项（资料类目）提审前补。
> 柜机 2026-11-21 上电，之后走第四节验收。

1. ✅ **确认小程序应用与 appid** —— **已完成（2026-11-20）**
   现有 `clients/alipay-mp-shell/mini.project.json` 里的 `2021007118617439` 已确认是**小程序**应用。
   注意网页应用 `2021007112617393`（wap 支付）与它**不能互换**：`trade.create` 只认小程序应用的 app_id。

2. **签约「小程序支付」产品** —— ⛔ **暂搁置（2026-11-19）**
   卡在商户资质，支付宝侧不给开。**支付产品未开通前，第 13 行那条「小程序内支付」是死路**
   （`alipay.trade.create` 会报「应用未开通该产品」），充值入口对用户等于不可用。
   代码不用退：`isConfigured()` fail-closed，未配 `ALIPAY_MP_ENABLED` 时不会误放行。
   恢复条件：商户资质补齐 → 签「小程序支付」→ 确认应用私钥/支付宝公钥在**小程序应用**里各配一次
   （可与网页应用共用密钥对，但公钥要各自上传）。

3. ✅ **配置「关联普通链接二维码」** —— **已完成（2026-11-20）**
   支付宝这套功能**不叫**「扫普通链接二维码打开小程序」——那是**微信**的叫法。支付宝侧名称是
   「**关联普通链接二维码**」，入口：`b.alipay.com` 商家平台 → 工作台 → 小程序 → 选中我们的小程序 →
   左侧菜单「**小程序码**」→「关联普通链接二维码」tab → 添加关联规则。

   要填四项：
   - **二维码地址**：`https://cabinet.easygovm.com/o/`（选**模糊匹配**，前缀命中即可，柜机号在路径末尾）
   - **校验二维码地址所属权**：下载校验文件 → 放到域名根目录（或最后一级子目录）→ 点「发起确认」
   - **小程序功能页**：`pages/index/index`（网页路径里的 `page` 参数）
   - **生效范围**：先选**体验版**测试，通过后再改线上版本，点「发布关联规则」约 2 分钟生效

   > ⚠️ 别和「**openURL 配置**」搞混（那个页面我们在后台也看到了）：openURL 是给 `my.ap.openURL`
   > 外跳 H5 用的白名单（上限 20 个地址）。我们**不走 H5**，这个页面**什么都不用配**。

   > 传参差异（已实测于源码，非推测）：微信走 `options.query.q`，支付宝走 `options.query.qrCode`，
   > 而且支付宝的码值**只进 App 启动参数，页面 `onLoad` 拿不到**（微信会透传到页面 query）。
   > `parseLaunchOptions()` 两者都认，`index.vue` 再用 `uni.getLaunchOptionsSync()` 兜底；
   > 单测 `launch-options.test.ts` 6 条、门禁 `check:alipay-mp-wiring` 判据 7/8 钉住。
   >
   > 微信侧那条**你已经配好且真机实测生效**。生效后微信扫码不再经过 `/o/` 服务端 302，
   > 柜机号靠 `q` 传进小程序。所以「`curl` 带 MicroMessenger UA 仍然 302」
   > **不是**规则没生效的证据：该规则由微信扫一扫客户端本地判定，根本不发 HTTP 请求。
   > 判断规则是否生效只认真机扫码。

4. **小程序基本资料**：类目（零售 / 自动售货）、隐私协议、用户协议、客服电话（官方会拨测）。

## 三、部署侧（配好才能生效）

服务端（`infra/.env.production`）：

```
ALIPAY_MP_ENABLED=true
ALIPAY_MP_APP_ID=2021007118617439      # 小程序应用的 appid
# 以下沿用网页应用那套（若小程序应用密钥不同，需另外提供）
ALIPAY_ENABLED=true
ALIPAY_PRIVATE_KEY=...
ALIPAY_PUBLIC_KEY=...
ALIPAY_NOTIFY_URL=https://<域名>/api/v2/payment/alipay/notify
```

客户端（`clients/consumer-mp/.env.production.local`）：

```
MP_ALIPAY_APPID=2021007118617439
```

然后：

```
pnpm --filter @aicabinet/consumer-mp build:mp-alipay   # 产物 dist/build/mp-alipay
```

支付宝开发者工具导入 `dist/build/mp-alipay`（`mini.project.json` 里的 appid 已注入），真机预览 → 上传 → 提审。

> ⚠️ 后端改动了 `PaymentController` 的请求体字段（`payScene`），**重建 trade-service 镜像**后才生效；
> `gen:api-types` 待重建容器后再跑一次（生成物里 `RechargePrepayRequest` 目前还没有该字段，
> 前端 body 由 `money-ui-contracts.ts` 自建，不影响运行）。

## 四、真机验收

### 前置（缺一项就白跑）

| 前置 | 判据 | 谁做 |
| --- | --- | --- |
| **柜机上电且 WS 在线** | 网关日志 `jiangyi ws connected … onlineDevices=1`；`POST /internal/v1/jiangyi/devices/<柜机>/door-state` 返回 204/200（离线时是 503「设备不在线」，开门同样 503） | 你（2026-11-21 上电） |
| **支付宝体验版已上传** | 开发者工具导入 `dist/build/mp-alipay` → 上传 → 生成体验版；自己的支付宝账号加进**体验成员** | 你 |
| 关联规则生效范围 = 体验版 | 扫柜门码能进小程序首页 | 已验证（2026-11-20） |
| 校验文件公网可达 | `https://cabinet.easygovm.com/o/<32位hash>.html` → 200 | 已验证（本机实测 200 text/html 32B） |
| 服务端支付宝登录可用 | `/api/v2/auth/alipay/login` 存在（旧镜像已有，无需重部署） | 已验证 |

> 🔴 **WS 空闲超时 90 秒**（`WebSocketConfig.setMaxSessionIdleTimeout(90_000)`）。柜机必须每 90 秒内有上行流量，
> 否则被踢下线、开门 503。上电后要盯一次连接时长，若将邑固件心跳间隔 > 90s 需调该值（否则投放后批量掉线）。

### 步骤

1. 支付宝扫柜门码 → **直接进小程序**（关联规则生效时不经过 `/o/` 服务端），**不停在登录页**。
2. 首次进入自动建档（`user_info.alipay_user_id` 有值、`phone_number = ali<userId>`），无需填手机号。
3. **给该账号人工调账**（开通支付产品前的替代充值）：
   `POST /api/v2/ops/admin/users/{userId}/balance`，body `{"deltaCents": 5000, "idempotencyKey": "<唯一串>"}`
   → `user_account.balance_cents` 变 5000，`balance_ledger` 记 `ADMIN_ADJUST`。
   **金额要 ≥ 预授权冻结额**：生产实测 `system_config.checkout.preauth_cents = 2000`（¥20），
   该柜 `device_info.deposit_cents` 未配（NULL）⇒ 按全局 ¥20 冻结，**建议调 ¥50** 留出取货余量
   （取值优先级：全局 0=关闭预授权 > 柜机押金 > 全局值 > `checkoutProperties` 兜底）。
4. 开门 → 取货 → 关门 → 余额扣款成功；订单详情「支付方式」显示账户余额。
5. ~~充值 1 元~~ —— **阻塞于小程序支付产品**，开通后再验：`my.tradePay` 拉起收银台 → 支付 → 余额 +1（`recharge_order` = `SUCCESS`）。
6. 余额不足时应给出明确提示（412「可用余额不足，开门需预授权冻结 ¥X」），**不能**留下永久 pending（再进页面不卡 12 秒）。

> ⚠️ 支付宝账号 `phone_number` 是 `ali<userId>` 占位、没有真实手机号 ⇒ 短信通知收不到、也无法按手机号与微信账号合并。
> 这是「静默登录不取手机号」的必然结果，属有意设计；要打通需在产品上补一次手机号绑定，不在本轮范围。

## 五、二期：做不做（明确结论）

| 项 | 结论 | 理由 |
| --- | --- | --- |
| 支付宝免密 / 芝麻先享（协议代扣） | ❌ **不建议做** | ① 依赖芝麻信用服务 ID，邀约制，商户资质这关都还没过；② 它的价值是「免确认开门」，而余额扣款已经是**零确认**（关门即扣），边际收益几乎为零；③ 代扣要额外做协议签约、解约、扣款失败重试三套流程，资金风险面反而变大。真要做也是「支付产品开通 + 有真实交易量」之后的事。 |
| 支付宝订阅消息 / 客服消息 | ⏸ **建议做，但排在最后** | 模板要单独申请，且有模板消息条数限制；当前短信已兜底（欠款催缴、扣款通知都有）。等支付宝端有真实用户再补，成本低、可随时插队。 |
| 支付宝账单对账口径复核 | ✅ **建议做，且要在第一笔真实交易之前** | 这不算「二期功能」，属于资金安全前置项：`AlipayPlatformBillProvider` 现在拿的是网页应用凭证，小程序支付的账期/手续费口径没核过。哪怕只跑 1 笔 1 元交易，也要先对一次总账，避免上线后对不平。 |
| 分享 / 朋友圈能力 | ⏸ 不做 | 微信专属能力，支付宝端无对应入口，已用 `isMpWeixin` 隐藏。 |

**推荐顺序**：支付产品开通（唯一硬阻塞）→ 支付宝端白名单试用 → 对账口径复核 → 放量 → 订阅消息 → （免密：基本可以永久不做）
