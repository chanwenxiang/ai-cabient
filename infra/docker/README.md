# Docker 镜像构建

基于 **JDK 17**（`eclipse-temurin:17`）多阶段构建。

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
docker build -f infra/docker/vision-service.Dockerfile -t ai-cabinet/vision-service:latest .
```

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
| `vision-service.Dockerfile` | `ai-cabinet/vision-service` |
