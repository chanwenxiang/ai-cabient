# Phase 7 指南 — Docker 生产镜像

## 变更摘要

| 项 | 说明 |
|----|------|
| JDK | 全项目统一 **Java 17**（Spring Boot 3.2 最低要求） |
| Docker | 三服务多阶段镜像 + 一键构建脚本 |
| 基础镜像 | `eclipse-temurin:17-jre-alpine` / `maven:3.9-eclipse-temurin-17` |

---

## JDK 17

根 `pom.xml`：

```xml
<java.version>17</java.version>
```

本地开发：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"   # 按本机路径调整
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn install -DskipTests
```

---

## 构建镜像

```powershell
cd ai-cabinet
.\infra\docker\build.ps1
```

产物：

- `ai-cabinet/trade-service:latest`
- `ai-cabinet/device-service:latest`
- `ai-cabinet/vision-service:latest`

---

## 与 docker-compose 联调

```powershell
# 1. 基础设施
cd infra && docker compose up -d

# 2. 构建镜像
cd .. && .\infra\docker\build.ps1

# 3. 运行 trade-service（示例）
docker run --rm -p 8080:8080 `
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/aicabinet `
  -e SPRING_DATASOURCE_USERNAME=aicabinet `
  -e SPRING_DATASOURCE_PASSWORD=aicabinet `
  -e AICABINET_DEVICE_SERVICE_URL=http://host.docker.internal:8081 `
  ai-cabinet/trade-service:latest
```

---

## 推送与 K8s

```bash
docker tag ai-cabinet/trade-service:latest registry.example.com/ai-cabinet/trade-service:v0.6.0
docker push registry.example.com/ai-cabinet/trade-service:v0.6.0
kubectl apply -f infra/k8s/
```

详见 `infra/k8s/README.md`、`infra/docker/README.md`。
