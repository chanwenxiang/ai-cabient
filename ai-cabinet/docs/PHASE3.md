# Phase 3 指南

## 新增能力

| 模块 | 内容 |
|------|------|
| 微信支付 | API v3 JSAPI 下单 + JSON 回调 + 关单/退款 + 余额到账 |
| 视频链路 | 关门携带 videoUri → 识别 → 结算 |
| Android | 录像占位 + MinIO 上传 |
| 运营 | 补货开门 API + 小程序页 |
| 账户 | 余额查询、OpenID 绑定 |

## 微信支付

### Mock 模式（默认）

`application.yml` 中 `aicabinet.wechat-pay.enabled: false`

小程序充值 → 自动调用 mock 回调 → 余额到账

```powershell
# 手动 mock 回调
curl -X POST http://localhost:8080/api/v2/payment/wechat/notify/mock/RXXXXXXXX

# 充值 E2E 联调（登录 → 预下单 → mock 支付 → 查余额）
.\scripts\e2e-recharge.ps1
```

### 充值 API

| 接口 | 说明 |
|------|------|
| `POST /api/v2/payment/recharge/prepay` | 创建充值预下单 |
| `GET /api/v2/payment/recharge/{orderId}` | 查询订单（PENDING 时会同步微信状态） |
| `POST /api/v2/payment/recharge/{orderId}/cancel` | 取消未支付订单（V3 关单） |
| `POST /api/v2/ops/admin/recharge/{orderId}/refund` | 运营退款（需 `ops:user:balance`） |

### 真实模式

设置环境变量：

```
WECHAT_APP_ID=wx...
WECHAT_MCH_ID=...
WECHAT_API_V3_KEY=...
WECHAT_MCH_SERIAL=...
WECHAT_PRIVATE_KEY=...
WECHAT_PLATFORM_CERT=...
WECHAT_NOTIFY_URL=https://your-domain/api/v2/payment/wechat/notify
```

并设置 `aicabinet.wechat-pay.enabled: true`

用户需先绑定 OpenID：`POST /api/v2/account/bind-openid?openId=xxx`

## 视频 + 识别流程

```
开门 → 开始录像 → 关门 → 上传 MinIO → MQTT 携带 videoUri
  → trade-service RECOGNIZING → vision-service → 扣款
```

模拟器关门时会附带：`minio://cabinet-videos/sim/{sessionId}.mp4`

## 运营补货

运营账号 `userId >= 100000000`（种子：手机号 `13900000001`，需单独登录注册或手动插入 token）

```
POST /api/v2/ops/restock/open-door
Authorization: Bearer <operator-token>
{"deviceId":"CAB-001"}
```

设备收到 `operatorMode=true`，只开门不自动结算。

## MinIO

```powershell
# docker compose 已包含 MinIO :9000
# 控制台 :9001  账号 minioadmin / minioadmin
# 创建 bucket: cabinet-videos
```

Android `build.gradle.kts` 中配置 `MINIO_ENDPOINT` 为工控机可访问的 IP。

## 测试账号

| 角色 | 手机号 | 验证码 | userId |
|------|--------|--------|--------|
| 消费者 | 13800138000 | 123456 | 10001 |
| 运营员 | 13900000001 | 123456 | 100000001（新注册） |

## Phase 4 计划

- CameraX 真实录像
- YOLO 视觉模型
- 微信真实 openId 登录（code2session）
- 争议工单后台
