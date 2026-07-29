# 商户门户平台说明 (M1–M5)

> **2026 重构**：商户 Web 已废弃，日常操作请用 **`clients/merchant-mp`**。运营后台开关在 **`clients/admin-vue`** → 商户分账。

商户 API 前缀 `/api/v2/merchant/**`。数据范围由 `ops_user_merchant` 绑定决定；未绑定商户的运营账号视为全局（admin 角色始终全局）。

## 平台能力分层（主流）

| 层级 | 谁控制 | 说明 |
|------|--------|------|
| 功能包 | 平台（商户与分账） | `pack_field/biz/team_enabled`：现场作业 / 经营工具 / 团队与设置 |
| 自助写开关 | 平台 | `allow_merchant_planogram_edit` / `allow_merchant_pricing_edit`（需对应功能包开启） |
| 角色权限 | 运营 RBAC | `merchant:*`；菜单管理中的商户树**仅用于授权**，不驱动小程序导航 |
| 小程序导航 | 前端固定 | `pages.json` + `config/merchant-nav.ts`，按 `hasPerm ∧ 功能包` 裁剪 |

`GET /api/v2/merchant/me` 返回 `enabledPacks`（绑定商户**并集**：任一范围内商户开包则 UI/鉴权可见该包）与已按功能包过滤的 `permissions`。API 鉴权同样要求 RBAC ∧ 功能包。

**数据查询按商户自身开包裁剪**（与并集鉴权不同）：订单/结算/定价等经营数据只返回 `pack_biz_enabled=true` 的商户及其柜机；现场作业、团队同理。关闭父商户功能包**不会级联**到子商户，需分别配置。

## 平台自助开关

| 开关 | 字段 | 默认 | 说明 |
|------|------|------|------|
| 现场作业包 | `pack_field_enabled` | `true` | 柜机 / 补货 / 待办 / 库存 |
| 经营工具包 | `pack_biz_enabled` | `true` | 订单 / 结算 / 定价 / 争议 / 分析 |
| 团队设置包 | `pack_team_enabled` | `true` | 商户设置 / 团队成员 |
| 允许商户改货道 | `allow_merchant_planogram_edit` | `false` | 关闭时 GET 货道可读，PUT 返回 403 |
| 允许商户改价 | `allow_merchant_pricing_edit` | `false` | 关闭时 GET 定价可读，PATCH 返回 403 |

运营在 **admin-vue → 商户与分账** 为每个商户切换。Demo 库 `MCH-DEFAULT` 在 `V50` 迁移中默认开启两写开关；功能包默认全开（`V122`）。

前端还需 RBAC：`merchant:slots:edit` / `merchant:pricing:edit`。`GET /me` 返回 `canEditPricing`（任一绑定商户开启改价）。

## 演示账号

| 角色 | 手机号 | 密码 | 商户 | user_id |
|------|--------|------|------|---------|
| 商户管理员 | 13800138001 | 123456 | MCH-DEFAULT | 100000002 |
| 商户店员（只读） | 13800138002 | 123456 | MCH-DEFAULT | 100000003 |
| 商户财务 | 13800138004 | 123456 | MCH-DEFAULT | 100000005 |
| 商户B管理员 | 13800138003 | 123456 | MCH-OTHER | 100000004 |
| 平台运营 | 13900000001 | 123456 | 全局 | 100000001 |

- 运营控制台：`http://localhost:8080/admin/`
- 商户小程序：见 [`clients/merchant-mp/README.md`](../clients/merchant-mp/README.md)

## 角色与权限

| 角色 | role_id | 说明 |
|------|---------|------|
| merchant_admin | 6 | 全量 `merchant:*` 权限 |
| merchant_staff | 7 | 只读：无 edit / export / reply / request |
| merchant_finance | 8 | 财务：结算/分账/分析/报表，无定价/要货/设置 |

## M7 增强

- **微信订阅消息**：`POST /notify/wx-bind`、`POST /notify/subscribe`
- **柜机详情**：温度展示 + 目标温度设置（在线时 MQTT 下发）
- **子角色 merchant_finance**：演示账号 `13800138004`

核心权限码（节选）：

- `merchant:portal:access` — 门户入口（Guard 强制）
- `merchant:devices:*` / `merchant:orders:*` / `merchant:inventory:view`
- `merchant:settlements:view|export` — M1 结算对账
- `merchant:pricing:view|edit` — M2 点位定价
- `merchant:disputes:list` + `merchant:disputes:reply` — M3 争议协同
- `merchant:replenishment:view|request` — M4 补货要货

## 数据隔离 (M5)

`MerchantScopeService` + `MerchantFeaturePackService` + `MerchantPortalGuard`：

1. **门户 Guard**：须 `merchant:portal:access`，且不能是全局运营账号。
2. **设备范围**：绑定商户含下级树；再按功能包过滤到「该商户已开对应包」的柜机。
3. **跨租户**：MCH-DEFAULT 用户访问 `CAB-OTHER`（他商户）→ HTTP 403。
4. **关包隔离**：父商户关经营包、子商户仍开时，父柜机订单/定价 403，子柜机仍可读。

## API 清单

| 模块 | 方法 | 路径 |
|------|------|------|
| 会话 | GET | `/me`, `/stats`, `/trend`, `/workbench` |
| 柜机 | GET/PATCH | `/devices`, `/devices/{id}`, `/devices/{id}/settings` |
| 订单 | GET | `/orders`, `/orders/{id}`, `/orders/export` |
| 争议 | GET/POST | `/disputes`, `/disputes/{id}`, `/disputes/{id}/reply` |
| 库存 | GET | `/inventory`, `/expiry-alerts`, `/slot-discrepancies` |
| 结算 | GET | `/settlements/overview`, `/daily`, `/batches`, `/export` |
| 分账 | GET | `/revenue-splits`, `/revenue-splits/export` |
| 定价 | GET/PATCH | `/pricing/skus`, `/pricing/skus/{skuId}`, `/pricing/history` |
| 补货 | GET/POST | `/replenishment/tasks`, `/suggestions`, `/requests` |
| 团队 | GET/POST | `/team/users` |
| 报表 | GET | `/device-reports`, `/device-reports/export` |

## 前端 403 处理

商户小程序 API 封装对 HTTP 403 展示服务端 `message` 或默认「无权限执行此操作」。

## 本地验证

```bash
# 后端
mvn install -pl services/common/common-core -am -q
mvn -pl services/trade-service test -q

# 移动客户端
cd clients/merchant-mp && npm run dev:mp-weixin

# API 冒烟
.\scripts\run-api-tests.ps1
```

跨租户 curl 示例（先登录取 token）：

```bash
curl -H "Authorization: Bearer $TOKEN_A" http://localhost:8080/api/v2/merchant/devices/CAB-OTHER
# MCH-DEFAULT → 403；MCH-OTHER → 200
```

## 路线图状态

| 里程碑 | 内容 | 状态 |
|--------|------|------|
| M1 | 财务闭环与结算对账 | ✅ |
| M2 | 点位定价与商品策略 | ✅ |
| M3 | 争议协同 | ✅ |
| M4 | 补货要货 | ✅ |
| M5 | 架构与权限加固 | ✅ |
| M6 | 商户小程序 + BI | ✅ |

## BI 分析 API (M6)

| 方法 | 路径 | 权限 |
|------|------|------|
| GET | `/analytics/overview?days=30` | `merchant:analytics:view` |
| GET | `/analytics/sku-sales?days=30&deviceId=` | 同上 |
| GET | `/analytics/velocity?deviceId=` | 同上 |
| GET | `/trend?days=7` | `merchant:trend:view` |

## M6+ 增强

- 商户小程序：争议详情/回复、补货要货、待办点击跳转
- 按 `merchant:*` 权限隐藏无权限菜单
- 冒烟：`scripts/run-api-tests.ps1`
