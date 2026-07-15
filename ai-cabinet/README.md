# AI Cabinet（AI 开门柜）

独立于 `easygo/ego-automat` 的新项目。旧代码仅作业务与硬件协议参考，不直接依赖。

## 架构概览

```
用户端 (小程序/App)
       │
       ▼
  trade-service  ←→  device-service  ←→  EMQX (MQTT)
       │                    │
       ▼                    ▼
  vision-service         edge/android-app
```

## 目录结构

| 路径 | 说明 |
|------|------|
| `services/trade-service` | 购物会话、订单、结算 |
| `services/device-service` | 设备注册、指令下发、状态同步 |
| `services/common/common-core` | 共享 DTO、枚举、工具 |
| `vision-service` | AI 视觉识别 (Python FastAPI) |
| `proto/` | MQTT / 内部通信 Protobuf 定义 |
| `infra/` | Docker Compose 本地开发环境 |
| `migration/` | 从旧 ego-automat 迁移脚本（参考用） |
| `clients/miniapp` | 微信小程序用户端（新建） |
| `edge/device-simulator` | 桌面设备模拟器（联调） |
| `docs/` | 设计文档 |

## 快速开始

> **本地完整联调**请直接阅读 **[docs/LOCAL_SETUP.md](docs/LOCAL_SETUP.md)**（含后端、小程序、模拟器、运营后台）。

> **启动速查**（服务、端口、账号密码）见 **[docs/STARTUP_REFERENCE.md](docs/STARTUP_REFERENCE.md)**。

> **YOLO 图片识别测试**见 **[docs/VISION_YOLO_TEST.md](docs/VISION_YOLO_TEST.md)**。

> **生产部署**请阅读 **[docs/PRODUCTION.md](docs/PRODUCTION.md)**（环境变量、安全清单、上线检查表）。

> **分步推进（时间充裕时推荐）**见 **[docs/ROADMAP.md](docs/ROADMAP.md)**（Step 1 全栈 Docker → 小程序 → vision → 生产）。

> **商业落地架构**见 **[docs/COMMERCIAL_ARCHITECTURE.md](docs/COMMERCIAL_ARCHITECTURE.md)**（OSS + 阿里云商品理解）。

> **运营模块（OTA / 风控 / 对账 / 补货 / SLA / RBAC）**见 **[docs/OPS_COMMERCIAL.md](docs/OPS_COMMERCIAL.md)**。

> **开发原则**：代码按生产标准编写（鉴权、内部 API、权限隔离），本地默认 `dev` profile 自动 mock，联调流程与线上一致。

> **环境要求**：JDK 17+、Docker、Maven 3.9+、Python 3.10+。若默认 Java 为 8，请先设置 `JAVA_HOME` 指向 JDK 17，例如：
> `set JAVA_HOME=C:\Program Files\Java\jdk-17`

### 1. 启动基础设施

```bash
cd infra
docker compose up -d
```

启动：PostgreSQL、Redis、EMQX、MinIO。

### 2. 启动后端服务

```bash
# 在项目根目录
mvn clean install -DskipTests

cd services/trade-service && mvn spring-boot:run
cd services/device-service && mvn spring-boot:run
```

### 3. 启动识别服务

```bash
cd vision-service
pip install -r requirements.txt
uvicorn app.main:app --reload --port 8082
```

### 4. 创建购物会话（需先登录拿 token，见 LOCAL_SETUP.md）

```bash
# 登录
curl -X POST http://localhost:8080/api/v2/auth/login \
  -H "Content-Type: application/json" \
  -d '{"phoneNumber":"13800138000","code":"123456"}'

# 创建会话（替换 <token>）
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
| Redpanda/Kafka | 9092 |
| PostgreSQL | **15433**（Docker 映射，避开 Windows 保留端口段） |
| Redis | 6379 |
| MinIO | 9000 |

## 与旧系统关系

- **不修改** `easygo/ego-automat`、`ego-automat-android`
- 业务规则参考：实名、余额/信用校验、补货开门不结算
- 硬件协议参考：`ChzhDevice8` 串口门锁（edge 层实现）
- 数据迁移见 `migration/README.md`

## 开发阶段

- [x] Phase 0：项目骨架
- [x] Phase 1：会话状态机 + MQTT 开门
- [x] Phase 2：JWT 登录 + Android + 小程序
- [x] Phase 3：微信支付 + 视频链路 + 运营补货
- [x] Phase 4：CameraX + YOLO + wx-login + 争议工单
- [x] Phase 5：MinIO 视频下载 + 运营 Web 后台 + 数据迁移脚本
- [x] Phase 6：API Gateway + Kafka 异步识别 + K8s + 视频预览
- [x] Phase 7：JDK 17 + Docker 生产镜像

详细联调见 `docs/PHASE1.md` ~ `docs/PHASE7.md`。

| 入口 | URL |
|------|-----|
| API Gateway | http://localhost/api/v2/ |
| 运营后台 | http://localhost/admin/index.html |
| 直连 trade | http://localhost:8080 |

Docker 镜像构建见 [`infra/docker/README.md`](infra/docker/README.md)。
