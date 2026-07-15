# Docker 镜像构建

基于 **JDK 17**（`eclipse-temurin:17`）多阶段构建。

## trade-service 与运营后台

`trade-service` 镜像在构建阶段执行 **Maven 全量 `package`**（不要加 `-Dskip.admin.build`）：

1. `frontend-maven-plugin` 在容器内安装 Node 24.18，执行 `npm install` + `npm run build`
2. 产物写入 `static/admin/` 并打进 Spring Boot JAR
3. 运行时访问 `http://<host>/admin/index.html`，与后端同域、版本一致

本地若已手动 `npm run build`，Docker 构建仍会重新打包，以镜像内构建结果为准。

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
