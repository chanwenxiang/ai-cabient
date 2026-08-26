# Docker Compose 部署

## 一键启动完整系统

在仓库根目录运行 `./docker-up.ps1`。命令会自动构建业务服务和设备模拟器镜像并启动全栈；仅复用现有镜像时可加 `-NoBuild`。停止使用 `./docker-down.ps1`。

本项目使用 **Docker Compose** 部署，不使用 Kubernetes。

## 两种模式

| 模式 | 适用场景 | 命令 |
|------|----------|------|
| **基础设施** | IDEA 本地跑 Java/Python 服务 | `docker compose up -d` |
| **全栈** | 单机生产 / 集成测试 | `docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps up -d --build` |

---

## 快速开始（全栈 dev）

```powershell
cd infra
copy .env.example .env
docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps up -d --build
```

或使用辅助脚本（自动复制 `.env`、可选 `--build`）：

```powershell
.\infra\up.ps1 -Build
```

**首次 `--build` 约需 3–8 分钟**（Maven 下载依赖 + 容器内构建运营后台 Vite）。后续有 BuildKit 缓存会快很多。

等待 **trade-service healthcheck 通过**（见下文）后访问：

| 服务 | 地址 |
|------|------|
| 运营后台 | http://localhost/admin/index.html |
| trade API（经 Gateway） | http://localhost/api/... |
| trade 直连 | http://localhost:8080 |
| Grafana | http://localhost:13000 (admin/admin，可通过 GRAFANA_PORT 修改) |
| Prometheus | http://localhost:9090 |
| DevOps 中心（后台） | http://localhost/admin/index.html#/devops |
| SonarQube / GHA Runner | 见 [docs/DEVOPS.md](../docs/DEVOPS.md)（`--profile devops`） |
| MinIO 控制台 | http://localhost:9001 (minioadmin/minioadmin) |
| EMQX 控制台 | http://localhost:28083 |

---

## 镜像构建

### 推荐方式：Compose 一键构建

全栈 profile 会为三个应用服务执行 `docker compose build`：

```powershell
cd infra
docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps up -d --build
```

仅重建单个服务：

```powershell
docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps up -d --build trade-service
```

### 脚本 / 手动构建

在项目根目录 `ai-cabinet/`：

```powershell
# Windows
.\infra\docker\build.ps1

# 指定 tag
.\infra\docker\build.ps1 -Tag v0.6.0
```

```bash
# Linux / macOS
./infra/docker/build.sh
```

单镜像：

```bash
docker build -f infra/docker/trade-service.Dockerfile -t ai-cabinet/trade-service:latest .
docker build -f infra/docker/device-service.Dockerfile -t ai-cabinet/device-service:latest .
docker build -f infra/docker/vision-service.Dockerfile -t ai-cabinet/vision-service:latest .
```

建议开启 BuildKit（Dockerfile 使用 `.m2` / Node 缓存）：

```powershell
$env:DOCKER_BUILDKIT=1
docker build -f infra/docker/trade-service.Dockerfile -t ai-cabinet/trade-service:latest .
```

### trade-service 与运营后台

`trade-service` 镜像在 **Maven `package` 阶段** 自动构建运营后台，**不要**加 `-Dskip.admin.build`：

1. Dockerfile 拷贝 `clients/admin-vue/` 与 `packages/shared-*`
2. `frontend-maven-plugin` 在容器内安装 Node 24.18 → `npm install` → `npm run build`
3. 产物写入 `static/admin/` 并打进 Spring Boot JAR
4. 运行时与 API 同域：`/admin/index.html`

详见 [`docker/README.md`](docker/README.md)。

### 构建产物与 tag

| Dockerfile | 镜像 | 说明 |
|------------|------|------|
| `docker/trade-service.Dockerfile` | `ai-cabinet/trade-service:${IMAGE_TAG:-latest}` | 含运营后台 + Flyway |
| `docker/device-service.Dockerfile` | `ai-cabinet/device-service:${IMAGE_TAG:-latest}` | MQTT 设备服务 |
| `docker/vision-service.Dockerfile` | `ai-cabinet/vision-service:${IMAGE_TAG:-latest}` | Python 识别服务 |

在 `infra/.env` 可设置 `IMAGE_TAG=v0.6.0` 固定版本。

### 本地 Maven 与 Docker 的关系

| 场景 | 做法 |
|------|------|
| IDEA 本地跑 Java、不改前端 | `mvn package -Pskip-admin-ui` 加快编译 |
| 打 Docker 镜像 | **必须**完整 `mvn package`（Dockerfile 已配置） |
| 只改运营控制台 | `cd clients/admin-vue && npm run build`，或重建 trade 镜像 |

---

## Healthcheck 与启动顺序

全栈模式（`docker-compose.apps.yml`）通过 **healthcheck + depends_on** 控制启动顺序，避免 trade 未完成 Flyway 迁移时 device/vision/gateway 已接入。

### 健康检查一览

| 服务 | 检查方式 | 说明 |
|------|----------|------|
| **postgres** | `pg_isready -U aicabinet -d aicabinet` | 每 5s，最多 10 次 |
| **trade-service** | `GET /actuator/health` 含 `"status":"UP"` | `start_period: 90s`（留足 Flyway + 首次 npm 构建后的 JVM 启动） |
| **device-service** | `GET /actuator/health` 含 `"status":"UP"` | `start_period: 45s` |
| **vision-service** | `GET /health` | `start_period: 60s`（模型加载可能较慢） |
| gateway | 无 HTTP healthcheck | 等待 trade + device + vision **healthy** 后启动 |

### 依赖链（简化）

```
postgres (healthy)
minio → minio-init (completed)
redpanda (started)
        ↓
trade-service (healthy)  ← 核心：DB 迁移 + Actuator UP
        ↓
├── device-service (healthy)
├── vision-service (healthy)
└── gateway (:80 → trade-service:8080)
```

### 查看健康状态

```powershell
cd infra
docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps ps
```

`STATUS` 列应出现 `(healthy)`（postgres、trade-service、device-service、vision-service）。

手动探测：

```powershell
# trade（容器内 Actuator）
curl http://localhost:8080/actuator/health

# vision
curl http://localhost:8082/health

# 经 Gateway 访问运营后台
curl -I http://localhost/admin/index.html
```

### trade 启动偏慢时

首次启动或清空 volume 后，trade 需：

- 连接 PostgreSQL 并执行 Flyway 迁移
- JVM + Spring Boot 冷启动

若 `docker compose ps` 长期显示 `trade-service` 为 `starting`，查看日志：

```powershell
docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps logs -f trade-service
```

常见原因：`.env` 缺少 `JWT_SECRET` / `INTERNAL_API_KEY`、PostgreSQL 未就绪、端口 8080 被占用。

---

## 生产部署

1. 复制 `infra/.env.example` → `infra/.env`
2. 设置 `SPRING_PROFILES_ACTIVE=prod`
3. 填写强密钥、微信 V3、SMS Webhook 等（见 [docs/PRODUCTION.md](../docs/PRODUCTION.md)）
4. 设置 `AICABINET_MOCK_ENABLED=false`、`VISION_MOCK_ENABLED=false`
5. 启动全栈：

```powershell
cd infra
docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps up -d --build
```

或使用：

```powershell
.\infra\up.ps1 -Build -Prod
```

6. 对外暴露 **80/443**（gateway），`/internal/**` 仅内网可达（nginx 已拦截）

---

## 仅基础设施（本地开发）

```powershell
cd infra
docker compose up -d
```

然后在 IDEA 启动 trade (8080)、device (8081)、vision (8082)。Gateway 通过 `host.docker.internal:8080` 转发（`gateway/nginx.conf`）。

此模式下 **不构建** 应用镜像，运营控制台使用 IDEA 内 `trade-service` 静态资源或 `clients/admin-vue` 本地 `npm run dev`。

---

## E2E 联调

全栈或本地服务启动后，且 trade healthcheck 已通过：

```powershell
# 充值链路
.\scripts\e2e-shopping.ps1
.\scripts\verify-local.ps1 -WithVision

# 购物链路（登录 → 开门 → 关门 → 识别 → 扣款）
.\scripts\e2e-shopping.ps1
```

---

## 故障排查

### `ports are not available: ... 1883`（Windows）

Docker Desktop 在 Windows 上常因 **Hyper-V 动态端口保留**（例如 `1822–1921`）导致默认 EMQX 端口 `1883` / `8883` / `18083` 无法绑定。

本项目已将宿主机映射改为 **11883 / 18883 / 28083**（可在 `.env` 中通过 `EMQX_MQTT_PORT` 等覆盖）。容器内仍使用 `emqx:1883`，Compose 内服务无需改配置。

部分 Windows 环境上 **11883 仍可能落在 Hyper-V 保留段**，本地常见改用 **12883**（`EMQX_MQTT_PORT=12883`）。`scripts/check-ports.ps1` 会同时探测 11883 与 12883。宿主机 MQTT URL 需与 `.env` 一致（例如脚本默认 `tcp://localhost:11883`，覆盖后改为 `12883`）。

查看本机保留端口：

```powershell
netsh interface ipv4 show excludedportrange protocol=tcp
.\scripts\check-ports.ps1
```

若仍冲突，在 `.env` 中把 `EMQX_MQTT_PORT` 改到未被保留的范围后 `docker compose up -d emqx`。

### `ports are not available: ... 5433`（Windows）

Hyper-V 常保留 **`5433–5532`**，导致 PostgreSQL 默认宿主机端口无法绑定。

本项目已改为 **`15433:5432`**（`.env` 中 `POSTGRES_PORT` 可覆盖）。Compose 内服务仍用 `postgres:5432`，无需改 `SPRING_DATASOURCE_URL`。

IDEA 本地连库时使用 `jdbc:postgresql://localhost:15433/aicabinet`。

--- `pip install` / `zlib.error`（解压 wheel 失败）

`requirements.txt` 中的 **ultralytics** 会拉取数百 MB 的 **torch**，网络不稳时易出现 `invalid stored block lengths`。

Compose 默认 **`VISION_INSTALL_ML=false`**，镜像只装基础依赖（`MOCK_ENABLED=true` 时足够 E2E）。需要真实 YOLO 时在 `.env` 设：

```env
VISION_INSTALL_ML=true
```

然后 `docker compose ... build vision-service`。ML 层会先装 CPU 版 torch 并带 `--retries 5`。

**Phase 1 冷启动（无自有标注，Retail-OS）**：叠加 `docker-compose.vision-local.yml`，见 [`docs/VISION_SKU_MODEL.md`](../docs/VISION_SKU_MODEL.md) §0。宿主机可用 `..\scripts\load-vision-dev-env.ps1` + `infra/.env.vision-dev.example`。

---

## 文件说明

| 文件 | 说明 |
|------|------|
| `docker-compose.yml` | PostgreSQL、Redis、EMQX、MinIO、Redpanda、Gateway、监控 |
| `docker-compose.apps.yml` | trade / device / vision 应用服务（profile: `apps`）、healthcheck、depends_on |
| `docker-compose.devops.yml` | SonarQube、GHA Runner（profile: `devops`），详见 [docs/DEVOPS.md](../docs/DEVOPS.md) |
| `docker-compose.vision-local.yml` | Phase 1 Retail-OS 本地 YOLO 叠加（INSTALL_ML + FORCE_REAL） |
| `.env.example` | 环境变量模板 |
| `.env.vision-dev.example` | 宿主机真实 YOLO 环境模板 |
| `up.ps1` | 全栈启动脚本（`-Build` / `-Down` / `-Prod`） |
| `gateway/nginx.conf` | 本地开发 gateway（转发 host.docker.internal） |
| `gateway/nginx.compose.conf` | 全栈 gateway（转发 trade-service 容器） |
| `docker/*.Dockerfile` | 多阶段镜像构建 |
| `docker/build.ps1` | 批量构建三个应用镜像 |

---

## 常用命令

```powershell
cd infra

# 查看日志
docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps logs -f trade-service

# 停止并删除容器（保留 volume）
docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps down

# 停止并删除 volume（清空数据库，慎用）
docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps down -v

# 重建单个服务
docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps up -d --build trade-service

# 仅拉基础设施
docker compose up -d
```
## Step 4 one-command runtime smoke

From `infra/`:

```powershell
.\up.ps1 -Build -Smoke
```

This command starts the full Docker stack, waits for `trade-service`, `device-service`, and `vision-service` healthchecks, then runs:

- `scripts/verify-production-readiness.ps1 -SkipBuild -SkipTests -SkipAdminBuild`
- `scripts/run-api-tests.ps1`
- `scripts/e2e-shopping.ps1`

`INTERNAL_API_KEY` is read from the current shell first, then from `infra/.env`.

## XXL-JOB 调度中心

随仓库根目录全栈启动（推荐）：

```powershell
.\docker-up.ps1
```

- 控制台：`http://localhost:18090/xxl-job-admin`（`admin` / `123456`）
- 已编入 `docker-compose.full.yml`；资金类任务见 [docs/SCHEDULED_TASK_MANAGEMENT.md](../docs/SCHEDULED_TASK_MANAGEMENT.md)
