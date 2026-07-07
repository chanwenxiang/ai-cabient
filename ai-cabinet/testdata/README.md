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

# Step 4 验证
cd ..
.\scripts\verify-step4.ps1 -SampleImage testdata\bottle.jpg -WithE2e
```

## 仅测 MinIO + YOLO 链路

用默认 `bus.jpg` 即可（会话通常会进入 **争议审核**，说明真实 YOLO 已跑通）：

```powershell
.\scripts\verify-step4.ps1
.\scripts\verify-step4.ps1 -WithE2e
```
