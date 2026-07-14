# 视觉识别测试素材

| 文件 | 用途 |
|------|------|
| `bottle.jpg` | 含瓶子/杯子的静态图，YOLO COCO → `SKU-DEMO-001` |
| `cola.png` | 备用静态图 |
| `take-one-bottle.mp4` | 合成视频：前几帧有瓶、末帧空白；**delta 模式应识别 taken=1** |
| `static-bottle.mp4` | 合成视频：全程有瓶；delta 模式 items 为空 → 人工审核 |

## 生成测试视频

```powershell
cd ai-cabinet/vision-service
pip install opencv-python-headless numpy
python scripts/generate_test_videos.py
```

## 真实 YOLO 识别（vision-service）

```powershell
cd ai-cabinet
.\scripts\load-vision-dev-env.ps1
cd vision-service
pip install -r requirements-ml.txt
python scripts\setup_yolo.py
python -m uvicorn app.main:app --port 8082
```

另开终端：

```powershell
cd ai-cabinet
.\scripts\verify-vision-model.ps1 -AllowGenericModel
```

## 上传 mp4 测试

```powershell
curl.exe -X POST "http://localhost:8082/api/v2/vision/recognize/upload" `
  -H "X-Internal-Api-Key: dev-vision-key-change-me" `
  -F "session_id=TEST-VIDEO" `
  -F "file=@testdata\take-one-bottle.mp4"
```

期望：`model_version` 含 `yolov8` 且非 `mock-v1`；`detected_classes` 含 `bottle`（取决于 YOLO 对合成帧的检测）。

## 端到端

```powershell
.\scripts\verify-local.ps1 -WithVision
.\scripts\e2e-shopping.ps1
```

用 `bus.jpg` 或不匹配 COCO 映射的视频时，会话会进入 **争议审核**，说明真实 YOLO 已跑通。
