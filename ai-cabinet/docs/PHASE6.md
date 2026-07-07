# Phase 6 指南

## 新增能力

| 模块 | 内容 |
|------|------|
| API Gateway | Nginx 统一入口 `:80` |
| Kafka 异步识别 | Redpanda + 可选 async 模式 |
| MinIO 视频预览 | 争议工单预签名播放 URL |
| K8s | `infra/k8s/` 生产部署清单 |

---

## API Gateway（Nginx）

docker compose 已包含 `gateway` 服务，将请求转发到本机 trade-service：

```
http://localhost/api/v2/...   →  trade-service:8080
http://localhost/admin/       →  运营后台
/internal/                    →  403 禁止外网访问
```

```powershell
cd ai-cabinet/infra
docker compose up -d gateway
# 需先在宿主机启动 trade-service
```

配置：`infra/gateway/nginx.conf`

---

## Kafka 异步识别

### 架构

```
关门 → trade-service RECOGNIZING
  → Kafka aicabinet.vision.recognize.request
  → vision-service worker
  → Kafka aicabinet.vision.recognize.result
  → trade-service 结算 / 争议
```

### 启用步骤

```powershell
# 1. 启动 Redpanda
cd ai-cabinet/infra && docker compose up -d redpanda

# 2. trade-service application.yml:
#    aicabinet.vision-async.enabled: true

# 3. vision-service
$env:KAFKA_ENABLED = "true"
$env:KAFKA_BOOTSTRAP = "localhost:9092"
uvicorn app.main:app --port 8082
```

默认 **关闭** async，保持同步识别行为。

### Topic

| Topic | 方向 |
|-------|------|
| `aicabinet.vision.recognize.request` | trade → vision |
| `aicabinet.vision.recognize.result` | vision → trade |

---

## 争议视频预览

`GET /api/v2/ops/disputes` 新增字段：

| 字段 | 说明 |
|------|------|
| `videoUri` | 原始 URI |
| `videoPreviewUrl` | MinIO 预签名 URL（默认 1h） |

运营后台已支持「预览购物视频」链接。

---

## Kubernetes

见 [`infra/k8s/README.md`](../infra/k8s/README.md)。

```bash
kubectl apply -f infra/k8s/namespace.yaml
kubectl apply -f infra/k8s/secrets.yaml
kubectl apply -f infra/k8s/configmap.yaml
kubectl apply -f infra/k8s/trade-service.yaml
kubectl apply -f infra/k8s/device-service.yaml
kubectl apply -f infra/k8s/vision-service.yaml
kubectl apply -f infra/k8s/ingress.yaml
```

---

## 端口

| 服务 | 端口 |
|------|------|
| API Gateway | 80 |
| trade-service | 8080 |
| Redpanda/Kafka | 9092 |
