# AI Cabinet（AI 开门柜）

独立于 `easygo/ego-automat` 的新项目。旧代码仅作业务与硬件协议参考，不直接依赖。

## 架构概览

```
消费者小程序 (consumer-mp) / 商户小程序 (merchant-mp) / 运营控制台 (admin-vue)
       │
       ▼
  trade-service  ←→  device-service  ←→  EMQX (MQTT)
       │                    │
       ▼                    ▼
  vision-service         edge/android-app
```

## 模块一览

| 路径 | 中文名 | 说明 |
|------|--------|------|
| `clients/admin-vue` | 运营控制台 | Vue3 + Element Plus，构建到 `/admin` |
| `clients/consumer-mp` | 消费者小程序 | 独立 uni-app 微信小程序 |
| `clients/merchant-mp` | 商户小程序 | 独立 uni-app 微信小程序 |
| `packages/shared-*` | 共享前端包 | types / api / dict / uni |
| `services/trade-service` | 交易服务 | 会话、订单、结算、运营 API |
| `services/device-service` | 设备服务 | MQTT、设备状态 |
| `services/common/common-core` | 公共核心库 | DTO、枚举 |
| `vision-service` | 视觉识别服务 | FastAPI + YOLO |
| `edge/device-simulator` | 设备模拟器 | 本地联调 |
| `edge/android-app` | 柜机端 App | Android |
| `infra/` | 基础设施 | Docker / 网关 |

完整模块说明见 **[docs/MODULES.md](docs/MODULES.md)**。

## 快速开始

> **本地完整联调**：[docs/LOCAL_SETUP.md](docs/LOCAL_SETUP.md)

> **启动速查**（端口、账号）：[docs/STARTUP_REFERENCE.md](docs/STARTUP_REFERENCE.md)

> **YOLO 识别测试**：[docs/VISION_YOLO_TEST.md](docs/VISION_YOLO_TEST.md)

> **生产部署**：[docs/PRODUCTION.md](docs/PRODUCTION.md)

> **前端产品决策**：[docs/FRONTEND_PRODUCT_DECISIONS.md](docs/FRONTEND_PRODUCT_DECISIONS.md)

> **商户平台 API**：[docs/MERCHANT_PLATFORM.md](docs/MERCHANT_PLATFORM.md)

> **开发原则**：代码按生产标准编写（鉴权、内部 API、权限隔离），本地默认 `dev` profile 自动 mock。

> **环境要求**：JDK 17+、Docker、Maven 3.9+、Node 18+、Python 3.10+。若默认 Java 为 8，请设置 `JAVA_HOME` 指向 JDK 17。

### 1. 启动基础设施

```bash
cd infra
docker compose up -d
```

### 2. 启动后端服务

```bash
mvn clean install -DskipTests "-Dskip.admin.build=true"

cd services/trade-service && mvn spring-boot:run
cd services/device-service && mvn spring-boot:run
```

### 3. 启动识别服务

```bash
cd vision-service
pip install -r requirements-base.txt
uvicorn app.main:app --reload --port 8082
```

### 4. 运营控制台（可选本地开发）

```bash
cd clients/admin-vue
npm install && npm run dev
```

生产静态资源构建：`npm run build`，产物复制到 `services/trade-service/.../static/admin/`。

### 5. 创建购物会话

```bash
curl -X POST http://localhost:8080/api/v2/auth/login \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"13800138000","code":"123456"}'

curl -X POST http://localhost:8080/api/v2/sessions \
  -H "Authorization: Bearer <token>" \
  -H "Content-Type: application/json" \
  -d '{"deviceId":"CAB-001"}'
```

## 端口

| 服务 | 端口 |
|------|------|
| trade-service | 8080 |
| device-service | 8081 |
| vision-service | 8082 |
| EMQX MQTT | 11883 |
| EMQX Dashboard | 28083 |
| API Gateway | 80 |
| PostgreSQL | **15433** |
| Redis | 6379 |
| MinIO | 9000 |

## 验证

```powershell
.\scripts\verify-local.ps1          # 服务健康 + 购物 E2E
.\scripts\verify-full.ps1           # 编译 + admin-vue 构建 + E2E
.\scripts\verify-production-readiness.ps1 -SkipRuntime
```

## 入口

| 入口 | URL |
|------|-----|
| API Gateway | http://localhost/api/v2/ |
| 运营控制台 | http://localhost:8080/admin/index.html |
| 直连 trade | http://localhost:8080 |

Docker 镜像构建见 [`infra/docker/README.md`](infra/docker/README.md)。

## 后续规划

- 消费者 uni-app 页面对齐完整开门购物流程
- 可观测性增强（Prometheus + Grafana）

## 与旧系统关系

- **不修改** `easygo/ego-automat`、`ego-automat-android`
- 业务规则参考：实名、余额/信用校验、补货开门不结算
- 硬件协议参考：`ChzhDevice8` 串口门锁（edge 层实现）
- 数据库由 Flyway 自动迁移，无需手工 ETL

