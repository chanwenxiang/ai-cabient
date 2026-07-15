# 微信小程序（用户端）

独立于旧小程序的新客户端，对接 `trade-service` `/api/v2` 接口。

## 功能

- 手机号 / 微信登录（JWT）
- 扫码或输入设备 ID → 创建购物会话 → 开门
- 轮询会话状态（中文提示 + 状态色条：购物中 / 识别中 / 待审核 / 失败）
- 底部 tabBar：首页（开门）+ 我的
- 展示账单、余额充值
- 运营：补货开门、争议审核（轻量，完整运营请用 Web 后台）

## 开发

1. 微信开发者工具导入本目录 `clients/miniapp`
2. 修改 `utils/api.js` 中 `BASE_URL`（本机 `http://localhost:8080`，真机预览用局域网 IP）
3. 开发阶段勾选「不校验合法域名」
4. 完整启动步骤见 **[docs/LOCAL_SETUP.md](../../docs/LOCAL_SETUP.md)**

### 开发账号（仅 dev 环境）

登录页在 `BASE_URL` 为 localhost / 局域网 IP 时会显示提示：

- 消费者：`13800138000` / `123456`
- 运营（Web 后台）：`13900000001` / `123456`

## 工程说明

| 文件 | 说明 |
|------|------|
| `utils/api.js` | API 封装、401 自动跳转登录 |
| `utils/common.js` | 错误格式化、会话状态中文文案与提示 |
| `pages/index` | 开门购物、余额展示、状态轮询（tabBar 首页） |
| `pages/login` | 登录（无硬编码测试账号） |
| `pages/mine` | 账户、订单/充值入口、运营工具（tabBar 我的） |
| `pages/recharge` | 充值（mock / 微信支付） |
| `pages/result` | 账单明细 |

## 与旧小程序关系

- **不修改** `easygo/dinngdang-wx` 等旧项目
- 旧接口 `/m8/v1/*`，新接口 `/api/v2/*`
- 用户数据通过 `migration/` 迁移后，userId 保持一致

## 页面

| 页面 | 路径 | 说明 |
|------|------|------|
| 登录 | pages/login | 手机号 / 微信登录 |
| 扫码开门 | pages/index | 创建会话、轮询状态 |
| 账单 | pages/result | 展示订单明细 |
| 我的 | pages/mine | 账户、订单/充值入口、运营工具、退出 |
| 购物订单 | pages/orders | 历史购物订单列表 |
| 充值记录 | pages/recharges | 历史充值记录 |
| 充值 | pages/recharge | 余额充值 |
| 运营补货 | pages/ops | 补货开门 |
| 争议审核 | pages/disputes | 人工结案 |
