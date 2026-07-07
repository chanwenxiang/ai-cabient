# 本地启动速查表

> 默认 **dev** 环境，验证码固定 `123456`。完整说明见 [LOCAL_SETUP.md](LOCAL_SETUP.md)。

---

## 一、启动顺序

| 顺序 | 组件 | 如何启动 |
|------|------|----------|
| 1 | Docker 基础设施 | `cd infra && docker compose up -d` |
| 2 | vision-service | `cd vision-service && uvicorn app.main:app --reload --port 8082` |
| 3 | trade-service | IDEA Run `TradeServiceApplication`（:8080） |
| 4 | device-service | IDEA Run `DeviceServiceApplication`（:8081） |
| 5 | 设备模拟器 | IDEA Run `DeviceSimulator`，参数 `CAB-001` |
| 6 | 微信小程序 | 微信开发者工具打开 `clients/miniapp` |
| 7 | 运营后台 | 浏览器打开 http://localhost:8080/admin/index.html |

---

## 二、服务与端口

### 必启（完整购物流程）

| 服务 | 端口 | 健康检查 / 入口 |
|------|------|-----------------|
| PostgreSQL | **15433** | Docker 内 5432，宿主机 15433 |
| **EMQX MQTT** | 11883 | 设备通信 |
| **MinIO API** | 9000 | 视频存储 |
| **trade-service** | **8080** | http://localhost:8080/actuator/health |
| **device-service** | **8081** | http://localhost:8081/actuator/health |
| **vision-service** | **8082** | http://localhost:8082/health |
| **设备模拟器** | — | 程序参数 `CAB-001`，每 30s 心跳 |

### 可选

| 服务 | 端口 | 说明 |
|------|------|------|
| Redis | 6379 | 预留，当前未用 |
| Redpanda/Kafka | 9092 | 异步识别用，默认关闭 |
| Nginx Gateway | 80 | http://localhost/admin/index.html |
| EMQX 控制台 | 28083 | http://localhost:28083 |
| MinIO 控制台 | 9001 | http://localhost:9001 |

### 前端入口

| 入口 | URL |
|------|-----|
| 运营后台 | http://localhost:8080/admin/index.html |
| 小程序 API | http://localhost:8080（`clients/miniapp/utils/api.js`） |
| API Gateway | http://localhost/api/v2/（需 gateway 容器 + trade 在宿主机） |

---

## 三、账号与密码

### 业务测试账号（小程序 / 运营后台登录）

| 角色 | 手机号 | 验证码 | userId | 说明 |
|------|--------|--------|--------|------|
| **消费者** | `13800138000` | `123456` | 10001 | 余额 100 元，用于开门购物 |
| **运营员** | `13900000001` | `123456` | 100000001 | 补货、运营后台、争议审核 |

- 登录接口：`POST /api/v2/auth/login`，body：`{"phoneNumber":"13800138000","code":"123456"}`
- dev 环境可直接输入 `123456`，不必先点「获取验证码」

### 设备

| 项 | 值 |
|----|-----|
| 设备 ID | `CAB-001` |
| 模拟器启动参数 | `CAB-001` |

### 商品

| SKU | 名称 | 价格 |
|-----|------|------|
| `SKU-DEMO-001` | 演示可乐 | 3.5 元 |

---

### 基础设施账号（Docker）

| 服务 | 地址 | 用户名 | 密码 |
|------|------|--------|------|
| **PostgreSQL** | localhost:**15433** / 库 `aicabinet` | `aicabinet` | `aicabinet` |
| **MinIO 控制台** | http://localhost:9001 | `minioadmin` | `minioadmin` |
| **MinIO API** | http://localhost:9000 | `minioadmin` | `minioadmin` |
| **EMQX 控制台** | http://localhost:28083 | `admin` | `public` |
| **Redis** | localhost:6379 | — | 无密码 |

MinIO 建议创建 bucket：`cabinet-videos`

---

### 开发环境内部密钥（一般不用改）

| 用途 | 配置项 | 默认值 |
|------|--------|--------|
| 服务间内部 API | `INTERNAL_API_KEY` / `X-Internal-Api-Key` | `dev-internal-key-change-me` |
| vision-service | `VISION_API_KEY` | `dev-vision-key-change-me` |
| JWT | `JWT_SECRET` | `ai-cabinet-dev-secret-key-32bytes!!` |

trade-service、device-service、vision-service 本地默认已对齐，无需手动配置。

---

## 四、最小联调检查

```text
✓ docker compose ps          → postgres / emqx / minio Running
✓ :8080/actuator/health      → trade-service UP
✓ :8081/actuator/health      → device-service UP
✓ :8082/health               → vision-service UP
✓ DeviceSimulator CAB-001    → 控制台有心跳日志
✓ 小程序 13800138000/123456  → 开门 CAB-001
✓ 后台 13900000001/123456    → 运营后台登录
```

---

## 五、相关文档

| 文档 | 内容 |
|------|------|
| [LOCAL_SETUP.md](LOCAL_SETUP.md) | 完整本地联调步骤 |
| [PRODUCTION.md](PRODUCTION.md) | 上生产环境变量与安全清单 |
