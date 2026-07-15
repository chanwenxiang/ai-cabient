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

### 一键 Step 4 验证（推荐）

```powershell
cd ai-cabinet
.\scripts\verify-step4.ps1              # upload + minio:// + 多摄融合 API
.\scripts\verify-step4.ps1 -WithE2e     # 再加 trade 实链路（bus.jpg 通常 DISPUTED）
.\scripts\verify-step4.ps1 -WithE2e -SampleImage testdata\bottle.jpg
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
| `VISION_API_KEY` | `dev-vision-key-change-me` | API 密钥 |

---

## 7. 常见问题

| 现象 | 处理 |
|------|------|
| `yolo_loaded: false` | 运行 `python scripts/setup_yolo.py`，检查 ultralytics 已安装 |
| `items` 为空 | 图片里没有 bottle/cup，换一张含瓶子的图 |
| `detected_classes` 有值但 items 空 | 检测到了其他类别（如 person），未映射到 SKU |
| 401 unauthorized | 请求头加 `X-Internal-Api-Key: dev-vision-key-change-me` |
