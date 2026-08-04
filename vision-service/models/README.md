# 视觉模型产物目录

权重文件（`*.pt`）默认 **不提交 Git**（见 `.gitignore`）。上线前将训练产物放入此目录或挂载卷。

## 命名约定

| 文件 | 说明 |
|------|------|
| `retail-os-v2.0.0.pt` | Retail-OS 76 类货架 SKU（**Phase 1 冷启动**本地识别，见 `scripts/download-retail-os-model.ps1` / `docs/VISION_SKU_MODEL.md` §0） |
| `retail-os-v2.0.0.manifest.json` | 76 类 class_names + `mapping_source=YOLO_RETAIL` |
| `cabinet-skus-v1.0.0.pt` | 自训 SKU 专用检测模型 |
| `cabinet-skus-v1.0.0.manifest.json` | 版本、SHA256、类别列表 |
| `yolov8n.pt` | 通用 COCO 基线（仅开发/预发链路验证） |

## 导出

```bash
cd vision-service
python training/train_sku_yolo.py --export-only runs/detect/cabinet-skus-v1/weights/best.pt --version cabinet-skus-v1.0.0
```

## Docker 构建

```powershell
# 将 cabinet-skus-v1.0.0.pt 放入本目录后：
cd ai-cabinet/infra
docker compose -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.production.yml `
  --env-file .env.production build vision-service
```

或运行时挂载：`VISION_MODEL_HOST_PATH=./models/production`（见 `docker-compose.production.yml`）。

## 灰度 / 回滚

1. 新版本命名为 `cabinet-skus-v1.1.0.pt`
2. 更新 `.env.production` 中 `YOLO_MODEL_VERSION` / `YOLO_MODEL_PATH`
3. `docker compose ... up -d --no-deps vision-service`
4. 观察 14 天准确率与争议率；异常时切回上一版本 manifest 中的 SHA256 文件
