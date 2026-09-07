# 视觉识别测试素材

| 文件 | 用途 |
|------|------|
| `bottle.jpg` | 静态图，供 mock / 上传接口联调 |
| `cola.png` | 备用静态图 |
| `take-one-bottle.mp4` | 合成视频：前几帧有瓶、末帧空白 |
| `static-bottle.mp4` | 合成视频：全程有瓶 |

> 云端自研 YOLO 已废弃。开发联调默认 `MOCK_ENABLED=true`；生产识别见 [VISION_QUECTEL_INTEGRATION.md](../docs/VISION_QUECTEL_INTEGRATION.md)。

## 生成测试视频

```powershell
cd ai-cabinet/vision-service
pip install opencv-python-headless numpy
python scripts/generate_test_videos.py
```

## mock 识别联调（vision-service）

```powershell
cd ai-cabinet/vision-service
.\.venv\Scripts\pip install -r requirements-base.txt
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8082
```

上传测试：

```powershell
curl.exe -X POST "http://localhost:8082/api/v2/vision/recognize/upload" `
  -H "X-Internal-Api-Key: dev-vision-key-change-me" `
  -F "session_id=TEST-VIDEO" `
  -F "file=@testdata\take-one-bottle.mp4"
```

期望：`model_version` 为 mock 系列；可用 `POST /api/v2/vision/debug/force-need-review` 强制进争议路径。

## 端到端

```powershell
.\scripts\verify-local.ps1 -WithVision
.\scripts\e2e-shopping.ps1
.\scripts\e2e-dispute-recognition.ps1
```
