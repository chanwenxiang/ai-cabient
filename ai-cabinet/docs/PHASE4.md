# Phase 4 指南

## 新增能力

| 模块 | 内容 |
|------|------|
| Android | CameraX 真实录像（无摄像头时回退占位文件） |
| vision-service | YOLOv8 推理骨架 + mock 双模式 |
| 微信登录 | 小程序 `wx.login` → code2session → JWT |
| 争议工单 | 识别置信度不足 → `DISPUTED` → 运营人工结案 |
| 小程序 | 微信一键登录、争议审核页 |

## 架构变化

```
关门 + videoUri
  → vision-service（YOLO 或 mock）
  → need_review=true → 创建 dispute_ticket，会话 DISPUTED
  → 运营 POST /ops/disputes/{id}/resolve → 手动结算 → COMPLETED
```

数据库迁移 **V5**：`dispute_ticket.items`、`resolution_items`、`sku_vision_mapping`。

---

## Android CameraX

`CabinetService` 继承 `LifecycleService`，`CameraXVideoRecorder` 绑定后置摄像头 SD 录像。

| 场景 | 行为 |
|------|------|
| 有 CAMERA 权限 | CameraX 写入 `cache/videos/{sessionId}.mp4` |
| 无权限 / 绑定失败 | 写入占位文本文件（触发争议审核） |
| MinIO 上传失败 | MQTT 携带 `file://` 本地路径 |

生产部署：

1. `MainActivity` 申请摄像头权限
2. `CabinetController(useMockDriver = false)` 接真实 Chzh 锁控
3. `build.gradle.kts` 配置 `MINIO_ENDPOINT`、`MQTT_BROKER` 为工控机可达 IP

---

## vision-service YOLO

### Mock 模式（默认）

未安装 ultralytics 或缺少模型文件时，返回 `SKU-DEMO-001` × 1。

`need_review` 规则：

- `video_uri` 为空 → 需审核
- `file://` 开头 → 需审核（本地占位/未上传）
- `minio://` 且置信度 ≥ 0.7 → 自动结算

### 启用 YOLO

```powershell
cd ai-cabinet/vision-service
pip install -r requirements.txt
pip install ultralytics opencv-python-headless

# 下载模型（约 6MB）
mkdir models
# 将 yolov8n.pt 放到 models/yolov8n.pt

$env:YOLO_MODEL_PATH = "models/yolov8n.pt"
uvicorn app.main:app --port 8082
```

健康检查：

```powershell
curl http://localhost:8082/health
# {"status":"ok","yolo_loaded":true,"model_version":"yolov8"}
```

COCO 类名 → SKU 映射见 `recognizer.py` 与 DB 表 `sku_vision_mapping`（`bottle`/`cup` → `SKU-DEMO-001`）。

> **Phase 5**：`minio://` URI 从 MinIO 下载后再推理（当前跳过，回退 mock）。

---

## 微信 code2session 登录

### Mock 模式（默认）

`application.yml` 中 `aicabinet.wechat-miniapp.enabled: false`

小程序点击「微信一键登录」→ 后端用 `mock_openid_{codeHash}` 创建/绑定用户。

可选绑定手机号：请求体带 `phoneNumber`，与已有手机号用户合并 OpenID。

```powershell
curl -X POST http://localhost:8080/api/v2/auth/wx-login `
  -H "Content-Type: application/json" `
  -d '{"code":"dev-test-code","phoneNumber":"13800138000"}'
```

### 真实模式

```yaml
aicabinet:
  wechat-miniapp:
    enabled: true
    app-id: ${WECHAT_MINIAPP_ID}
    app-secret: ${WECHAT_MINIAPP_SECRET}
```

小程序 `app.json` 需配置合法 AppID，并在微信后台配置服务器域名。

---

## 争议工单

### API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/v2/ops/disputes` | 列出 OPEN 工单 |
| POST | `/api/v2/ops/disputes/{ticketId}/resolve` | 人工指定商品后结算 |

请求体示例：

```json
{
  "items": [{ "skuId": "SKU-DEMO-001", "quantity": 1 }]
}
```

### 小程序

首页 →「争议审核」→ 运营账号登录后处理待审工单。

### 本地联调（触发争议）

设备模拟器默认 `minio://` URI 会自动结算。要测试争议流程，对**已开门**的会话发送 `file://` 关门事件：

```powershell
# 1. 登录获取 token
$login = curl -s -X POST http://localhost:8080/api/v2/auth/login `
  -H "Content-Type: application/json" `
  -d '{"phoneNumber":"13800138000","code":"123456"}' | ConvertFrom-Json
$token = $login.data.token

# 2. 创建会话（模拟器会开门）
$session = curl -s -X POST http://localhost:8080/api/v2/sessions `
  -H "Authorization: Bearer $token" `
  -H "Content-Type: application/json" `
  -d '{"deviceId":"CAB-001"}' | ConvertFrom-Json
$sid = $session.data.sessionId

# 3. 等待模拟器关门后，或直接 POST 带 file:// 的关门（覆盖）
curl -X POST http://localhost:8080/internal/v1/sessions/door-event `
  -H "Content-Type: application/json" `
  -d "{`"sessionId`":`"$sid`",`"doorState`":`"CLOSED`",`"videoUri`":`"file:///tmp/test.mp4`"}"

# 4. 查询会话 → DISPUTED
curl http://localhost:8080/api/v2/sessions/$sid -H "Authorization: Bearer $token"

# 5. 运营列出并结案
curl http://localhost:8080/api/v2/ops/disputes -H "Authorization: Bearer $token"
curl -X POST "http://localhost:8080/api/v2/ops/disputes/{ticketId}/resolve" `
  -H "Authorization: Bearer $token" `
  -H "Content-Type: application/json" `
  -d '{"items":[{"skuId":"SKU-DEMO-001","quantity":1}]}'
```

---

## 启动步骤

与 Phase 1–3 相同，确保 vision-service 在 **8082** 运行：

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
cd ai-cabinet
mvn install -DskipTests

cd vision-service && uvicorn app.main:app --port 8082
# 另开终端：device-service、trade-service、device-simulator
```

---

## 测试账号

| 角色 | 手机号 | 验证码 | 说明 |
|------|--------|--------|------|
| 消费者 | 13800138000 | 123456 | userId 10001 |
| 运营员 | 13900000001 | 123456 | userId ≥ 100000000 |

---

## Phase 5 计划

- MinIO 视频下载 + YOLO 全链路
- 争议后台 Web UI
- 旧系统 `ego-automat` 数据迁移脚本
- API 网关 / Kafka / 生产 K8s
