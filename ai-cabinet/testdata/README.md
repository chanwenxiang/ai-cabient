# 视觉识别测试素材

| 文件 | 用途 |
|------|------|
| `bus.jpg` | 默认样本（ultralytics 自带），YOLO 可识别 bus/person，**通常不会**映射到 SKU-DEMO-001 |
| `bottle.jpg` | 可选：含瓶子/杯子的照片，用于验证自动扣款（映射 SKU-DEMO-001） |

## 准备 bottle.jpg（可选）

找一张含**瓶子或杯子**的图片，保存为 `testdata/bottle.jpg`，然后：

```powershell
# 严格识别模式（无 mock 兜底）
$env:MOCK_ENABLED = "false"
cd vision-service
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8082

# 上传识别（见 docs/VISION_YOLO_TEST.md）
curl.exe -X POST "http://localhost:8082/api/v2/vision/recognize/upload" `
  -H "X-Internal-Api-Key: dev-vision-key-change-me" `
  -F "session_id=TEST-BOTTLE" `
  -F "file=@testdata\bottle.jpg"
```

## 端到端

```powershell
.\scripts\verify-local.ps1 -WithVision
.\scripts\e2e-shopping.ps1
```

用默认 `bus.jpg` 做关门视频时，会话通常会进入 **争议审核**，说明真实 YOLO 已跑通。
