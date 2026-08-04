# 前端产品决策

## 1. 商户 Web 废弃

**决策：废弃商户全功能 Web 门户。**

- 商户日常操作统一在 **`clients/merchant-mp`** 独立微信小程序完成
- 运营侧平台开关（改价 / 改货道）在 **`clients/admin-vue`** → 商户分账
- `/merchant` 静态资源已移除；旧 URL 返回 404

## 2. 消费者端

**决策：消费者与商户拆分为两个独立微信小程序。**

| 项目 | 路径 | 说明 |
|------|------|------|
| 消费者 | `clients/consumer-mp` | 扫码开门、购物车、订单 |
| 商户 | `clients/merchant-mp` | 概览、柜机、定价、待办 |

- 页面路径为扁平 `pages/*`（非 `pages/consumer/*` / `pages/merchant/*`）
- 两个小程序互不跳转，各自独立 AppID
- API 与旧 miniapp 对齐（`/api/v2/*`，非 `/api/v2/consumer/*`）
- 原 `clients/uni-app` 已删除，功能由两个独立小程序承接

## 3. 运营后台

**决策：`clients/admin-vue` 为唯一运营 Web 前端。**

- Vue3 + Element Plus + TypeScript
- Vite 构建产物写入 `services/trade-service/.../static/admin/`
- Maven `frontend-maven-plugin` 默认 workingDirectory 为 `clients/admin-vue`

## 4. 已删除客户端（2026 仓库清理）

以下目录已从仓库移除，功能由上表模块替代：

| 原路径 | 替代 |
|--------|------|
| `clients/admin` | `clients/admin-vue` |
| `clients/merchant` | `clients/merchant-mp` |
| `clients/miniapp` | `clients/consumer-mp` |
| `clients/merchant-miniapp` | `clients/merchant-mp` |
| `clients/uni-app` | `clients/consumer-mp` + `clients/merchant-mp` |

## 5. 共享包

admin-vue 通过 Vite alias 引用 `packages/shared-types`、`packages/shared-api`、`packages/shared-dict`，避免重复定义 DTO 与字典。

uni-app 小程序通过 Vite alias 引用 `packages/shared-uni`（二维码解析、会话状态文案、金额格式化）。

两个小程序的 `build:mp-weixin` 均为生产构建，必须显式提供真实 HTTPS `VITE_API_BASE_URL`；
消费者端仅 `build:mp-weixin:dev` 会自动探测局域网地址并生成开发构建。

## 6. 文档

模块路径与启动方式见 [MODULES.md](MODULES.md)。商户 API 见 [MERCHANT_PLATFORM.md](MERCHANT_PLATFORM.md)。
