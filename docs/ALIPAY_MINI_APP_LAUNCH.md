# 支付宝小程序（消费者端）上线清单

> 状态：**代码侧已接通**（2026-11-19，MA-363）；真机验收等微信侧测试通过后再做。
> 本文只列「还差什么」，按「谁能做」分组：平台侧（必须你在支付宝后台点）→ 部署侧 → 验收。

## 一、代码侧已完成（可复核）

| 环节 | 实现 | 复核方式 |
| --- | --- | --- |
| 编译通路 | `@dcloudio/uni-mp-alipay` 同版本平台包 + `manifest.json` 的 `mp-alipay` 节点 | `pnpm --filter @aicabinet/consumer-mp build:mp-alipay:dev` |
| appid 注入 | `scripts/inject-alipay-mp-env.mjs`：release 缺 `MP_ALIPAY_APPID` 直接失败（避免 `touristappid` 包） | 不配变量跑 `build:mp-alipay` 应红 |
| 静默登录 | `my.getAuthCode(scopes=auth_base)` → `POST /api/v2/auth/alipay/login`（服务端 `alipay.system.oauth.token` 换 user_id 建档） | 真机扫码后应直接进首页 |
| 小程序内支付 | `alipay.trade.create`（`buyer_id` + `product_code=JSAPI_PAY`）→ 客户端 `my.tradePay({tradeNO})` → 轮询到账 | 充值 1 元，看钱包余额 |
| 落地页分流 | `/o/{deviceId}`：支付宝 UA → 先小程序承接页（`alipays://`），未配小程序才回落 H5/引导页 | `curl -A AlipayClient https://<域名>/o/<柜机号>` |
| 门禁 | `check:alipay-mp-wiring`（登录/支付/落地页/构建注入 8 类判据 + 15 条负向夹具） | `pnpm check:alipay-mp-wiring` |
| 单测 | `AlipayPayClientMiniTest`（4 条：金额单位/产品码/buyer_id/缺 trade_no fail-closed） | `mvn -pl services/trade-service -am test -Dtest=AlipayPayClientMiniTest` |

## 二、平台侧（阻塞项，必须先做）

1. **确认小程序应用与 appid**
   现有 `clients/alipay-mp-shell/mini.project.json` 里的 `2021007118617439` 是「芝麻先享审核载体」，请确认它在开放平台的应用类型是**小程序**（不是网页/生活号）。
   网页应用 `2021007112617393`（wap 支付用）与它**不能互换**：`trade.create` 只认小程序应用的 app_id。

2. **签约「小程序支付」产品**
   商家平台 → 产品中心 → 小程序支付 → 签约。未签约时 `alipay.trade.create` 会报「应用未开通该产品」。
   同时确认应用私钥/支付宝公钥已在**这个小程序应用**里配置（可与网页应用共用同一套密钥对，但公钥要各自上传一次）。

3. **配置「扫普通链接二维码打开小程序」**（决定一码两用体验）
   开放平台 → 小程序 → 开发管理 → 扫普通链接二维码打开小程序：规则填 `https://<对外域名>/o/`，并上传校验文件。
   配好后同一张柜门码在支付宝里**直接进小程序**，不经过承接页。未配置时走 `alipays://` 兜底（部分机型 WebView 会拦截，用户需按页面提示操作）。

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

## 四、真机验收（等微信侧通过后做）

1. 支付宝扫柜门码 → 直接进小程序（或经承接页自动跳转），**不停在登录页**。
2. 首次进入自动建档（`user_info.alipay_user_id` 有值），无需填手机号。
3. 充值 1 元：拉起支付宝收银台 → 支付 → 余额 +1（`recharge_order` 变 `SUCCESS`）。
4. 开门 → 取货 → 关门 → 余额扣款成功；订单详情「支付方式」显示账户余额。
5. 余额不足时充值后再结算；取消支付不应留下永久 pending（再进页面不卡 12 秒）。

## 五、有意未做（二期）

| 项 | 说明 |
| --- | --- |
| 支付宝免密/芝麻先享（协议代扣） | 需要小程序内签约组件 + 芝麻信用服务 ID（邀约制）。当前结算走「余额」已可闭环。 |
| 订阅消息 / 客服消息 | 支付宝模板消息与微信不是一套，需单独申请模板；当前用短信兜底。 |
| 支付宝账单对账 | `AlipayPlatformBillProvider` 已存在，但账单下载走的是网页应用凭证，小程序支付的分账口径需复核。 |
| 分享/朋友圈能力 | 微信专属（`open-type=share`）已用 `isMpWeixin` 隐藏，支付宝端无对应入口。 |
