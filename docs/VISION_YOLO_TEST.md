# 真实 YOLO 图片识别测试

本地用 YOLOv8 检测图片中的瓶子/杯子，并映射为 `SKU-DEMO-001`（演示可乐）。

---

## 1. 安装依赖

在 PyCharm Terminal 或命令行：

```powershell
cd c:\Users\cwx\OneDrive\Desktop\demo\ai-cabinet\vision-service
.\.venv\Scripts\Activate.ps1
pip install ultralytics opencv-python-headless
```

---

## 2. 下载模型

```powershell
python scripts\setup_yolo.py
```

成功后会生成 `vision-service\models\yolov8n.pt`。

> 也可跳过此步：启动服务时设置 `YOLO_AUTO_DOWNLOAD=true`（默认开启）会自动下载。

---

## 3. 重启 vision-service

PyCharm 运行 `run.py`，然后访问：

http://localhost:8082/health

应看到：

```json
{
  "yolo_loaded": true,
  "model_version": "yolov8"
}
```

---

## 4. 测试方式

### 一键验证（推荐）

```powershell
cd ai-cabinet
.\scripts\verify-local.ps1 -WithVision
.\scripts\e2e-shopping.ps1
```

测试素材说明见 `testdata/README.md`。

### A. 上传图片（推荐）

准备一张**含有瓶子或杯子**的图片（YOLO COCO 预训练类别）。

PowerShell：

```powershell
curl.exe -X POST "http://localhost:8082/api/v2/vision/recognize/upload" `
  -H "X-Internal-Api-Key: dev-vision-key-change-me" `
  -F "session_id=TEST-BOTTLE" `
  -F "file=@C:\path\to\bottle.jpg"
```

成功示例：

```json
{
  "session_id": "TEST-BOTTLE",
  "model_version": "yolov8",
  "items": [{"sku_id": "SKU-DEMO-001", "quantity": 1, "confidence": 0.85}],
  "detected_classes": ["bottle"],
  "need_review": false
}
```

- `detected_classes`：YOLO 实际检测到的 COCO 类别
- 若图片里没有 bottle/cup：`items` 为空，`need_review: true`（会进人工审核）

### B. 本地文件路径

```powershell
curl.exe -X POST "http://localhost:8082/api/v2/vision/recognize" `
  -H "X-Internal-Api-Key: dev-vision-key-change-me" `
  -H "Content-Type: application/json" `
  -d "{\"session_id\":\"TEST001\",\"video_uri\":\"file:///C:/temp/bottle.jpg\"}"
```

路径用正斜杠 `C:/temp/bottle.jpg`。

### C. 接入完整购物流程（推荐）

完整链路：

```
小程序开门 → trade-service → device-service → MQTT → 模拟器关门
  → 上报 videoUri → trade-service → vision-service(YOLO) → 结算扣款
```

#### 步骤 1：准备测试图片

找一张**含瓶子/杯子**的图片，例如 `C:\temp\bottle.jpg`。

#### 步骤 2：配置模拟器（推荐：自动上传 MinIO）

贴近生产：关门前把本地图上传到 MinIO，再上报 `minio://...`，vision 从 MinIO 下载后 YOLO。

IDEA 运行 **DeviceSimulator** 时，在 Run Configuration 里加：

| 变量 | 值 |
|------|-----|
| `AICABINET_SIM_VIDEO_FILE` | `C:\temp\bottle.jpg` |

Program arguments 仍为 `CAB-001`。MinIO 默认 `http://localhost:9000` / `minioadmin` / `minioadmin`，bucket `cabinet-videos`（不存在会自动创建）。

PowerShell：

```powershell
$env:AICABINET_SIM_VIDEO_FILE = "C:\temp\bottle.jpg"
```

**重启 DeviceSimulator**（改环境变量后必须重启）。

启动日志应出现：`video mode=MinIO upload file=...`；关门时应看到 `uploaded ... -> minio://cabinet-videos/sim/...`。

#### 步骤 3：确认全部服务运行

| 服务 | 端口 |
|------|------|
| Docker (postgres/emqx/minio) | — |
| vision-service | 8082，`yolo_loaded: true` |
| trade-service | 8080 |
| device-service | 8081 |
| DeviceSimulator | CAB-001 + `AICABINET_SIM_VIDEO_FILE` |

#### 步骤 4：小程序走完整流程

1. 登录 `13800138000` / `123456`
2. 设备 `CAB-001` → 开门
3. 等约 5 秒（模拟器：开门 3s → 上传 MinIO → 关门 → 识别）
4. 小程序跳转账单，应扣 **3.5 元**

#### 步骤 5：验证识别结果

| 位置 | 看什么 |
|------|--------|
| 模拟器控制台 | `uploaded ... -> minio://cabinet-videos/sim/...` |
| MinIO 控制台 http://localhost:9001 | bucket `cabinet-videos` 下有 `sim/<sessionId>.jpg` |
| vision-service 控制台 | YOLO predict / minio download |
| trade-service 控制台 | `door closed, recognizing` → `session completed` |
| 运营后台 → 会话 | `videoUri` 为 `minio://cabinet-videos/sim/...` |
| 运营后台 → 订单 | 有订单，SKU-DEMO-001 |

若 YOLO 未识别到 bottle/cup（`need_review: true`），会话会进 **争议审核**，在运营后台人工结案。

#### 备选：跳过 MinIO（file://）

仅测 YOLO、不验证对象存储时：

```powershell
$env:AICABINET_SIM_VIDEO_URI = "file:///C:/temp/bottle.jpg"
```

（`AICABINET_SIM_VIDEO_URI` 优先于 `AICABINET_SIM_VIDEO_FILE`。）

---


---

## 4b. 视频识别（mp4 / delta 模式）

生成测试视频：

```powershell
cd ai-cabinet/vision-service
python scripts\generate_test_videos.py
```

加载真实识别环境并启动：

```powershell
cd ai-cabinet
.\scripts\load-vision-dev-env.ps1
cd vision-service
python -m uvicorn app.main:app --port 8082
```

上传 mp4：

```powershell
curl.exe -X POST "http://localhost:8082/api/v2/vision/recognize/upload" `
  -H "X-Internal-Api-Key: dev-vision-key-change-me" `
  -F "session_id=TEST-VIDEO" `
  -F "file=@..\testdata\take-one-bottle.mp4"
```

一键验证：

```powershell
cd ai-cabinet
.\scripts\verify-vision-model.ps1 -AllowGenericModel
```

成功时 `model_version` 为 `yolov8-delta` 或类似（**非** `mock-v1`）。

---

## 5. 类别与 SKU 映射

当前硬编码在 `app/recognizer.py`：

| YOLO 检测类别 | SKU | 商品 |
|--------------|-----|------|
| bottle | SKU-DEMO-001 | 演示可乐 3.5 元 |
| cup | SKU-DEMO-001 | 演示可乐 3.5 元 |

数据库表 `sku_vision_mapping` 有相同映射（V5 迁移），后续可改为读库。

**注意**：预训练 YOLO 只能识别 COCO 80 类（人、车、瓶子等），**不能**识别具体品牌饮料。  
要识别「康师傅」「可口可乐」等，需用自家商品数据**训练专用模型**。

---

## 6. 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `YOLO_MODEL_PATH` | `models/yolov8n.pt` | 模型路径 |
| `YOLO_AUTO_DOWNLOAD` | `true` | 首次启动自动下载 |
| `YOLO_CONF` | `0.5` | 检测置信度阈值 |
| `YOLO_REVIEW_CONF` | `0.7` | 低于此值进人工审核 |
| `YOLO_RECOGNITION_MODE` | `delta` | `single_frame` 或 `delta`（开门前后差异） |
| `VISION_FORCE_REAL` | `false` | `true` 时禁用静默 mock（trade 仍可 mock） |
| `MOCK_ENABLED` | `true` | vision-service 全局 mock |
| `VISION_API_KEY` | `dev-vision-key-change-me` | API 密钥 |

开发真实识别推荐：`.\scripts\load-vision-dev-env.ps1`（见 `infra/.env.vision-dev`）。

---

## 8. 沙箱模拟全栈（真实 YOLO + 支付宝沙箱）

### 8.1 环境准备

```powershell
cd ai-cabinet\infra
copy .env.sandbox.example .env.sandbox
# 编辑 .env.sandbox，填写 ALIPAY_APP_ID / ALIPAY_PRIVATE_KEY / ALIPAY_PUBLIC_KEY
# 可选：ALIPAY_NOTIFY_URL 公网 HTTPS；无隧道时支付后由 H5 轮询同步

cd ..
.\scripts\start-sandbox-stack.ps1 -Build
```

关键变量（`.env.sandbox.example`）：

| 变量 | 说明 |
|------|------|
| `AICABINET_STAGING_MODE=false` | 本地 Docker 勿开 `true`（会触发 ProductionStartupValidator，需强密钥 + SMS webhook） |
| `AICABINET_MOCK_ENABLED=false` | 禁止 trade 重力购物车覆盖 YOLO |
| `VISION_MOCK_ENABLED=true` + `VISION_FORCE_REAL=true` | 本地 Docker 推荐组合：允许 dev API key 启动，但推理走真实 YOLO（勿单独设 `VISION_MOCK_ENABLED=false`，否则 vision 容器会因缺生产密钥起不来） |
| `AICABINET_SIM_VIDEO_FILE` | 柜机上传素材，如 `/testdata/bottle.jpg` 或 `/testdata/your.mp4` |
| `AICABINET_SIM_GRAVITY_JSON=[]` | 清空模拟重力购物车 |
| `ALIPAY_GATEWAY` | 沙箱 `https://openapi-sandbox.dl.alipaydev.com/gateway.do` |

### 8.2 支付宝沙箱凭证（你需提供）

1. [支付宝开放平台](https://open.alipay.com) → 沙箱环境
2. 复制 **APPID**、**应用私钥 RSA2**、**支付宝公钥**
3. 沙箱 **买家账号** 用于 WAP 支付页登录
4. 本地无公网时：消费者 H5「我的」→ 支付宝沙箱充值 → 支付后返回自动 `GET /payment/recharge/{orderId}` 同步

### 8.3 扫码模拟（无真实扫码头）

| 方式 | 操作 |
|------|------|
| H5 手动 | 首页 → 手动输入 `CAB-001` |
| URL | `http://localhost:5174/#/?deviceId=CAB-001` |
| 脚本 | `.\scripts\e2e-real-vision-shopping.ps1` |

柜机摄像头素材由 **device-simulator** 上传，与扫码无关。

### 8.4 真实购买视频

1. 将 mp4 放入 `testdata/`，例如 `testdata/purchase.mp4`
2. `.env.sandbox` 设置 `AICABINET_SIM_VIDEO_FILE=/testdata/purchase.mp4`
3. `docker compose --env-file .env.sandbox -f docker-compose.full.yml up -d --force-recreate device-simulator`
4. 先探针：`curl` 上传 vision `/recognize/upload` 确认 SKU/置信度
5. 全链路：`.\scripts\e2e-real-vision-shopping.ps1 -VideoFile purchase.mp4 -ExpectedState ANY`

### 8.5 测试矩阵

| 场景 | 素材 | 预期 |
|------|------|------|
| S1 | `soda.jpg` | `COMPLETED`（雪碧阈值 80%）；`bottle.jpg` 视置信度可能 `DISPUTED`（默认 92%） |
| S2 | `take-one-bottle.mp4` | delta taken=1 |
| S3 | 自备真实 mp4 | 视映射与置信度 |
| S5 | 支付宝沙箱充 ¥20 后购物 | 余额减少 + 订单 PAID |

### 8.6 扣款置信度阈值（`min_charge_confidence`）

识别置信度 **≥ 该 SKU 阈值** 才会自动扣款；低于阈值进入 `DISPUTED` 人工审核。字段在 `sku_catalog.min_charge_confidence`，默认 **92%**；演示雪碧 `SKU-SODA-001` 种子为 **80%**（`soda.jpg` YOLO 约 82%）。

**以后怎么改（任选其一）：**

| 方式 | 适用场景 | 操作 |
|------|----------|------|
| 运营后台编辑 | 可视化调整 | 登录 `http://localhost/admin` → **商品管理** →「编辑阈值」→ 滑块保存 |
| 运营 API | 线上/沙箱即时调整 | `PUT /api/v2/ops/admin/skus/{skuId}`，body 带 `minChargeConfidence`（0.5–1.0，如 `0.85`）；需 `ops:sku:edit` 权限 |
| SQL | 本地调试 | `UPDATE sku_catalog SET min_charge_confidence = 0.85 WHERE sku_id = 'SKU-SODA-001';` |
| Flyway 迁移 | 生产/团队统一基线 | 新增 `Vxx__*.sql` 写 `UPDATE sku_catalog SET …`（见 `V60__demo_soda_min_charge_confidence.sql`） |
| Demo 重置 | 恢复演示数据 | `POST /internal/v1/demo/ensure`（或 `.\scripts\seed-demo-data.ps1 -Ensure`）会把雪碧重置回 80%、其余 SKU 92% |

API 示例（先登录拿 token）：

```bash
curl -X PUT "http://localhost:18080/api/v2/ops/admin/skus/SKU-SODA-001" \
  -H "Authorization: Bearer <token>" -H "Content-Type: application/json" \
  -d '{"skuId":"SKU-SODA-001","skuName":"雪碧 500ml","priceCents":400,"minChargeConfidence":0.85}'
```

改完后无需重启服务；**下一笔购物会话**起生效。可先 `curl` vision 探针或 `e2e-real-vision-shopping.ps1` 验证是否 `COMPLETED` / `DISPUTED`。

---

## 9. 常见问题

| 现象 | 处理 |
|------|------|
| `yolo_loaded: false` | 运行 `python scripts/setup_yolo.py`，检查 ultralytics 已安装 |
| `items` 为空 | 图片里没有 bottle/cup，换一张含瓶子的图 |
| `detected_classes` 有值但 items 空 | 检测到了其他类别（如 person），未映射到 SKU |
| 401 unauthorized | 请求头加 `X-Internal-Api-Key: dev-vision-key-change-me` |
