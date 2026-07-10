# AI开门柜 · 消费者小程序

`@aicabinet/consumer-mp` — 独立微信小程序，面向消费者扫码开门购物。

## 页面

| 路径 | 说明 |
|------|------|
| `pages/login/login` | 登录（验证码默认，支持密码） |
| `pages/index/index` | 首页 · 扫码开门、购物车 |
| `pages/orders/orders` | 订单列表 |
| `pages/mine/mine` | 我的 |
| `pages/result/result` | 账单结果 |

TabBar：首页 / 订单 / 我的

## 开发

```bash
cd clients/consumer-mp
npm install
npm run dev:mp-weixin    # 开发
npm run build:mp-weixin  # 构建
```

用微信开发者工具导入 **`dist/dev/mp-weixin`**（开发）或 **`dist/build/mp-weixin`**（生产）。

> 改代码后若界面没变化：先停掉旧的 `npm run dev:mp-weixin`，再重新运行；微信开发者工具 **清缓存 → 全部清除 → 编译**。确认导入目录是 `consumer-mp/dist/dev/mp-weixin`，不是 `clients/uni-app`。

## 依赖

- `@aicabinet/shared-types` — DTO 类型
- `@aicabinet/shared-uni` — 二维码解析、会话状态、格式化

## 配置

- `src/manifest.json` → `mp-weixin.appid` 填入微信小程序 AppID
- 开发环境 API 由 `npm run dev:mp-weixin` 自动写入本机局域网 IP（微信开发者工具无法稳定访问 `localhost`）
- 也可手动执行：`node ../../scripts/sync-consumer-mp-api.mjs`
- 微信开发者工具：**详情 → 本地设置 → 勾选「不校验合法域名、web-view、TLS 版本以及 HTTPS 证书」**
- 请用 `npm run dev:mp-weixin` 编译开发版（不要用 build 产物测本地接口）
