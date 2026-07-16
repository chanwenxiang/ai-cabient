# 生产环境部署指南

本文说明如何将 AI 开门柜从**本地开发模式**切换到**生产模式**。本地开发默认 `SPRING_PROFILES_ACTIVE=dev`，生产必须显式设为 `prod`。

> **代码只有一套**：本地走 mock 分支，上线改环境变量即可，无需 fork 或替换代码。

---

## 1. 环境模式对比

| 项 | 开发 (`dev`) | 生产 (`prod`) |
|----|-------------|---------------|
| Mock 登录/支付 | 固定验证码 `123456` | 关闭，必须真实 SMS + 微信支付 |
| 内部 API | 需 `X-Internal-Api-Key`（有默认值） | 必须强密钥，仅内网可达 |
| 微信 | 可 mock openId | 必须配置 AppId/Secret + 商户号 |
| 视觉识别 | 无模型时 mock 自动结算 | 无模型时强制 `need_review` |
| 启动校验 | 警告提示 | 缺配置则**拒绝启动** |

---

## 2. 必填环境变量（生产）

### trade-service

```bash
SPRING_PROFILES_ACTIVE=prod

# 数据库
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/aicabinet
SPRING_DATASOURCE_USERNAME=aicabinet
SPRING_DATASOURCE_PASSWORD=<强密码>

# 安全（至少 32 字符，勿用默认值）
JWT_SECRET=<随机32+字符>
INTERNAL_API_KEY=<随机32+字符>
VISION_API_KEY=<随机32+字符>

# SMS：POST JSON {"phoneNumber":"138...","code":"123456"}
SMS_WEBHOOK_URL=https://your-sms-gateway/send

# 微信（API v3）
WECHAT_APP_ID=wx...
WECHAT_MCH_ID=...
WECHAT_NOTIFY_URL=https://your-domain/api/v2/payment/wechat/notify
WECHAT_API_V3_KEY=<32字节APIv3密钥>
WECHAT_MCH_SERIAL=<商户API证书序列号>
WECHAT_PRIVATE_KEY=<商户API私钥PEM，单行或\\n>
WECHAT_PLATFORM_CERT=<微信支付平台证书PEM，可选>
WECHAT_PLATFORM_CERT_AUTO_FETCH=true
WECHAT_MINIAPP_ID=wx...
WECHAT_MINIAPP_SECRET=...

# MinIO / 对象存储（本地 MinIO；生产可改为阿里云 OSS S3 兼容 endpoint）
MINIO_ENDPOINT=https://oss-cn-shanghai.aliyuncs.com
MINIO_ACCESS_KEY=...
MINIO_SECRET_KEY=...
MINIO_BUCKET=cabinet-videos
OSS_REGION=cn-shanghai
OBJECT_STORAGE_SCHEME=oss

# 服务地址（Docker Compose 内网）
AICABINET_DEVICE_SERVICE_URL=http://device-service:8081
AICABINET_VISION_SERVICE_URL=http://vision-service:8082

# CORS（运营后台域名）
CORS_ORIGIN=https://ops.your-domain.com

# 微信分账（需微信支付 V3 已配置；余额购物订单提交时需填 wxTransactionId）
# 本地联调可另设 PROFIT_SHARING_MOCK_ENABLED=true（不调微信 API；生产必须 false）
PROFIT_SHARING_ENABLED=true
PROFIT_SHARING_MOCK_ENABLED=false
PROFIT_SHARING_RETRY_ENABLED=true
PROFIT_SHARING_RETRY_BATCH_SIZE=20
WECHAT_PAY_ENABLED=true

# 免密购物扣款：未接入真实支付分/支付宝代扣前保持 false
PAYSCORE_ENABLED=false
PAYSCORE_LIVE_CHARGE_ENABLED=false
PAYSCORE_CHARGE_GATEWAY_URL=https://pay-gateway.example.com
PAYSCORE_CHARGE_GATEWAY_API_KEY=<随机32+字符>
```

### device-service

```bash
SPRING_PROFILES_ACTIVE=prod
INTERNAL_API_KEY=<与 trade-service 相同>
AICABINET_TRADE_SERVICE_URL=http://trade-service:8080
MQTT_BROKER=ssl://emqx:8883
MQTT_CLIENT_ID=device-service-prod-<city-or-cluster>
MQTT_USERNAME=<emqx user>
MQTT_PASSWORD=<随机16+字符>
MQTT_PERSISTENCE_DIR=/data/aicabinet/mqtt-paho
```

### vision-service

```bash
VISION_API_KEY=<与 trade-service aicabinet.vision-api.key 相同>
MOCK_ENABLED=false
RECOGNIZER_BACKEND=yolo
YOLO_RECOGNITION_MODE=delta

# SKU 专用模型（§10 真实扣款前必须）
YOLO_MODEL_PATH=/app/models/cabinet-skus-v1.0.0.pt
YOLO_MODEL_VERSION=cabinet-skus-v1.0.0
YOLO_AUTO_DOWNLOAD=false
YOLO_CONF=0.45
YOLO_REVIEW_CONF=0.72

# 对象存储（生产 OSS）
MINIO_ENDPOINT=https://oss-cn-shanghai.aliyuncs.com
MINIO_ACCESS_KEY=<OSS AccessKeyId>
MINIO_SECRET_KEY=<OSS AccessKeySecret>
OSS_REGION=cn-shanghai

TRADE_SERVICE_URL=http://trade-service:8080
INTERNAL_API_KEY=<与 trade 相同>

KAFKA_ENABLED=true
KAFKA_BOOTSTRAP=redpanda:9092
```

详见 [ARCHITECTURE.md](ARCHITECTURE.md) 商业落地一节。

---

## 3. 启动命令示例

```bash
# trade-service
export SPRING_PROFILES_ACTIVE=prod
export JWT_SECRET=$(openssl rand -base64 48)
export INTERNAL_API_KEY=$(openssl rand -base64 48)
export VISION_API_KEY=$(openssl rand -base64 48)
# ... 其余变量
java -jar trade-service.jar

# vision-service
export MOCK_ENABLED=false
export VISION_API_KEY=$VISION_API_KEY
uvicorn app.main:app --host 0.0.0.0 --port 8082
```

生产启动时 `ProductionStartupValidator` 会校验：JWT/内部密钥非默认值、SMS Webhook 已配置、微信支付与小程序均已启用。

---

## 4. 网络安全清单

1. **`/internal/**` 不得暴露公网**  
   - Nginx Gateway 已拦截（`infra/gateway/nginx*.conf`）  
   - 所有服务间调用携带 `X-Internal-Api-Key`

2. **MQTT 生产必须 TLS + ACL**  
   - 禁用 `EMQX_ALLOW_ANONYMOUS`  
   - 每台柜机独立用户名/证书

3. **vision-service 仅内网**  
   - `/api/v2/vision/*` 需 API Key  
   - `/health` 可给探针用

4. **运营后台**  
   - 建议独立域名 + HTTPS + IP 白名单/VPN  
   - JWT 存 localStorage 有 XSS 风险，后续可改 httpOnly Cookie

---

## 5. SMS Webhook 协议

生产环境 trade-service 向 `SMS_WEBHOOK_URL` 发送：

```json
POST /your-path
Content-Type: application/json

{
  "phoneNumber": "13900000001",
  "code": "482913"
}
```

对接阿里云/腾讯云短信时，在 Webhook 层转调官方 SDK 即可。

---

## 5.1 微信分账生产配置

| 变量 | 说明 |
|------|------|
| `PROFIT_SHARING_ENABLED` | `true` 启用分账 API（prod 下若开启则要求微信支付 V3 完整配置） |
| `PROFIT_SHARING_MOCK_ENABLED` | 本地联调 Mock（不调微信）；**生产必须 `false`** |
| `WECHAT_PAY_ENABLED` | 启用微信支付 V3 客户端（真机分账需 `true` 并配齐证书） |
| `PROFIT_SHARING_RETRY_ENABLED` | 失败单自动重试（默认 `true`，每 15 分钟） |
| `PROFIT_SHARING_RETRY_BATCH_SIZE` | 单次重试批大小（默认 20） |

**上线步骤：**

1. 商户后台添加分账接收方，将 `wechatReceiverId` 写入运营后台「商户分账」  
2. 确认 `WECHAT_*` 支付证书与 `WECHAT_PLATFORM_CERT_AUTO_FETCH=true`  
3. 设置 `PROFIT_SHARING_ENABLED=true` 并重启 trade-service  
4. 运营后台「商户分账」页查看 **分账状态** 面板应为「API 就绪」  
5. 购物订单当前为**余额扣款**：分账需在列表点「提交」并填写对应 `wxTransactionId`（充值/支付流水号）

Admin API：`GET /api/v2/ops/admin/merchants/profit-sharing/status`

---

## 6. Docker Compose 部署

本项目使用 **Docker Compose** 部署（不使用 Kubernetes）。详见 [`infra/README.md`](../infra/README.md)。

### 快速启动（生产）

```powershell
cd infra
copy .env.example .env
# 编辑 .env：SPRING_PROFILES_ACTIVE=prod，填写密钥与微信 V3 等
docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps up -d --build
```

或使用辅助脚本：

```powershell
.\infra\up.ps1 -Build
```

### 环境变量

复制 `infra/.env.example` 为 `infra/.env`，**勿将真实密钥提交 Git**。生产必填项：

| 变量 | 说明 |
|------|------|
| `JWT_SECRET` | ≥32 字符 |
| `INTERNAL_API_KEY` | 服务间调用 |
| `VISION_API_KEY` | vision 识别 |
| `POSTGRES_PASSWORD` | 数据库密码 |
| `WECHAT_*` | 微信支付 V3 + 小程序 |
| `SMS_WEBHOOK_URL` | 短信网关 |
| `AICABINET_MOCK_ENABLED` | 生产设为 `false` |
| `VISION_MOCK_ENABLED` | 生产设为 `false` |

对外通过 **Gateway :80** 暴露 API 与运营后台；数据库/Redis/MQTT/MinIO 端口建议仅内网开放。

### 镜像构建

见 [`infra/docker/README.md`](../infra/docker/README.md)。

`trade-service` 镜像构建时会通过 Maven **自动打包运营控制台**（`clients/admin-vue` → Vite → `static/admin`），无需单独构建前端镜像；Compose `--build` 即可得到含 `/admin/index.html` 的完整服务。

### E2E 验证

```powershell
.\scripts\e2e-shopping.ps1
.\scripts\verify-local.ps1
```

### 预发 / 上线前验证

```powershell
# 检查生产 .env 必填项（不启动服务）
.\scripts\check-env.ps1 -CheckEnv -Prod

# 预发：compose + env 检查 + 购物 E2E
copy infra\.env.staging.example infra\.env.staging
.\scripts\deploy-staging.ps1
```

| 文件 | 说明 |
|------|------|
| `infra/.env.staging.example` | 预发环境变量模板（mock 关闭、微信可留空） |
| `infra/docker-compose.staging.yml` | 叠加 sms-webhook-mock + `SPRING_PROFILES_ACTIVE=staging` |
| `scripts/sms-webhook-mock.py` | 本地/容器 SMS 接收器，供 webhook 联调 |

正式上线：将 `SPRING_PROFILES_ACTIVE=prod`，填写全部 `WECHAT_*`，`MQTT_BROKER=ssl://...`，`VISION_MOCK_ENABLED=false`，配置 SKU 模型见 [`VISION_SKU_MODEL.md`](VISION_SKU_MODEL.md)。

生产 Compose：

```powershell
docker compose -p ai-cabinet -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.production.yml --env-file .env.production --profile apps up -d --build
```

---

## 7. 已实现的生产能力

| 能力 | 说明 |
|------|------|
| 分环境配置 | `application-dev.yml` / `application-prod.yml` |
| 商业架构（OSS + 阿里云识别） | [ARCHITECTURE.md](ARCHITECTURE.md) 商业落地一节 |
| 内部 API 鉴权 | trade + device `/internal/**` |
| 会话 IDOR 防护 | 用户只能查自己的 session/order |
| 争议/运营 API | 需 operator 账号 (userId ≥ 100000000) |
| 审计日志 | 运营操作写入 `admin_audit_log` |
| 支付 | prod 强制微信签名验证；mock 端点仅 dev |
| 全局异常 | 统一 `ApiResponse` 格式，不泄露堆栈 |

---

## 8. 上线前检查表

- [ ] `SPRING_PROFILES_ACTIVE=prod`，服务能正常启动
- [ ] 更换所有默认密钥（JWT、INTERNAL_API_KEY、VISION_API_KEY）
- [ ] 微信商户号、小程序、支付回调 URL 已配置并验签通过
- [ ] SMS Webhook 实测能收到验证码
- [ ] YOLO 模型已打入 vision 镜像，`MOCK_ENABLED=false`
- [ ] MinIO bucket 已创建，生命周期策略已设
- [ ] EMQX TLS + 设备 ACL 已配置
- [ ] Ingress 不暴露 `/internal/**`
- [ ] 数据库备份与 Flyway 迁移已在预发验证
- [ ] 运营账号已创建（非种子 123456 依赖）

---

## 9. 本地开发不受影响

默认 `dev` profile 仍可使用：

- 验证码 `123456`（日志中可见）
- Mock 微信支付 `/api/v2/payment/wechat/notify/mock/{orderId}`
- 内部 API 默认 key：`dev-internal-key-change-me`

详见 [LOCAL_SETUP.md](LOCAL_SETUP.md)。
