# Docker 镜像构建

Java 服务基于 **JDK 17**（`eclipse-temurin:17`）多阶段构建；PostgreSQL 使用钉住摘要的官方镜像并替换 `gosu`。

## trade-service 与运营后台

`trade-service.Dockerfile` 默认使用 **`-Pskip-admin-ui`**，把宿主机已构建的 `services/trade-service/src/main/resources/static/admin/` 打进 JAR（避免 BuildKit 缓存导致运营台 CSS/JS 陈旧）。

改 `clients/admin-vue` 后请先构建再打镜像：

```powershell
npm --prefix clients/admin-vue run build
docker build -f infra/docker/trade-service.Dockerfile -t ai-cabinet/trade-service:latest .
```

运行时经网关访问 `http://<host>/admin/index.html`，与后端同域。若需容器内重新跑 Node/`frontend-maven-plugin`，去掉 Dockerfile 中的 `-Pskip-admin-ui`。

## 构建

在项目根目录 `ai-cabinet/` 执行：

```powershell
# Windows
.\infra\docker\build.ps1

# Linux / macOS
chmod +x infra/docker/build.sh
./infra/docker/build.sh
```

指定 tag：

```powershell
.\infra\docker\build.ps1 -Tag v0.6.0
```

## 单服务构建

```bash
docker build -f infra/docker/trade-service.Dockerfile -t ai-cabinet/trade-service:latest .
docker build -f infra/docker/device-service.Dockerfile -t ai-cabinet/device-service:latest .
```

## PostgreSQL gosu 安全镜像

官方 `postgres:16-alpine` 的 `/usr/local/bin/gosu` 由 Go 1.24.6 构建，命中 `CVE-2025-68121`（Go 1.24.13/1.25.7 起修复，见 `GO-2026-4337`）。`postgres.Dockerfile` 钉住官方基线摘要，用 Alpine `gosu=1.19-r6`（Go 1.26.9）替换该二进制并升级 `libxml2=2.13.9-r3`，最后用 `FROM scratch + COPY --from=patched / /` 扁平化合并文件系统，避免 Trivy 继续报告基础层被覆盖的旧 `gosu`。

```bash
docker build -f infra/docker/postgres.Dockerfile -t ai-cabinet/postgres:16-gosu-flat .
```

需要替换现有 PostgreSQL 时，显式叠加 `infra/docker-compose.postgres-hardened.yml`；该 overlay 不进入默认部署链，避免无意重建数据库。升级前必须完整备份并验证数据卷挂载不变。

## 部署（Docker Compose）

推荐使用 Compose 全栈启动，见 [`../README.md`](../README.md)：

```powershell
cd infra
copy .env.example .env
docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps up -d --build
```

## 文件

| Dockerfile | 镜像 |
|------------|------|
| `trade-service.Dockerfile` | `ai-cabinet/trade-service` |
| `device-service.Dockerfile` | `ai-cabinet/device-service` |
| `postgres.Dockerfile` | `ai-cabinet/postgres:16-gosu-flat` |
