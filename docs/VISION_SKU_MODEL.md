# SKU 专用视觉模型与生产部署

对齐 [`BROWSER_MIN_UAT.md`](BROWSER_MIN_UAT.md) §10 / §12 阶段 F。

## 目标

| 阶段 | 模型 | 映射 | 资金 |
|------|------|------|------|
| 开发 mock | （无 ML） | — | mock 余额 |
| **冷启动（无自有标注）** | **`retail-os-v2.0.0.pt`** | **`YOLO_RETAIL`（V61）** | 演示/测试；**非真钱准确率** |
| 预发 | `yolov8n` 或早期 SKU | 混合 | 测试余额 |
| 灰度/生产 | **`cabinet-skus-v*.pt`** | **`YOLO_SKU`** | 真实扣款前须 §10 准确率达标 |

**禁止** 用通用 COCO 模型 + `bottle→SKU` 映射做生产真实资金结算。  
**禁止** 把 Retail-OS 冷启动当成生产结算准确率已达标。

---

## 0. 无自有数据：Retail-OS 冷启动（Phase 1）

新项目往往没有柜内实拍与标注。此时先跑通**服务端本地 YOLO**（与竞品主路径同构），再用公开货架图验收；自训放到有柜有货之后。

| 项 | 值 |
|----|-----|
| 权重 | `vision-service/models/retail-os-v2.0.0.pt`（已有则跳过下载） |
| 下载 | `.\scripts\download-retail-os-model.ps1`（失败可用 `-UseHfShelfFallback` 单类兜底） |
| 映射 | Flyway `V61__retail_os_vision_mapping.sql` → `YOLO_RETAIL` |
| 后端 | `RECOGNIZER_BACKEND=yolo`（主链路不用 DeepSeek） |
| 真实推理 | `VISION_FORCE_REAL=true`（失败 → `need_review`，不静默假 SKU） |

**宿主机 uvicorn**

```powershell
.\scripts\load-vision-dev-env.ps1   # 加载 infra/.env.vision-dev
cd vision-service
# 需已 pip install -r requirements-ml.txt
.\.venv\Scripts\python.exe -m uvicorn app.main:app --port 8082
```

**Docker（本仓库 Windows 全栈常用 full.yml，宿主机端口 18082）**

```powershell
cd infra
docker compose -p ai-cabinet `
  -f docker-compose.full.yml -f docker-compose.win-ports.yml -f docker-compose.vision-local.yml `
  --env-file .env build vision-service
docker compose -p ai-cabinet `
  -f docker-compose.full.yml -f docker-compose.win-ports.yml -f docker-compose.vision-local.yml `
  --env-file .env up -d vision-service
```

也可仅设 `.env`：`VISION_INSTALL_ML=true`、`VISION_FORCE_REAL=true`、`RECOGNIZER_BACKEND=yolo` 后按原 full 栈重建。

**验收（无柜内实拍）**

| 检查 | 期望 |
|------|------|
| `GET :18082/health`（Windows full 栈）或 `:8082` | `yolo_loaded=true`，`vision_force_real=true`，`recognizer_backend=yolo`，`model_version=retail-os-v2.0.0` |
| 上传识别 | 使用**真实货架/商品照片**；仓库内 `datasets/cabinet-retail-v1` 的 `retail_*` 多为色块占位图，Retail-OS **不会**检出，属正常 |
| 无检出 / 低置信 | `items=[]` 且 `need_review=true`，**不会**静默返回 mock SKU |
| 映射 | trade `GET /internal/v1/vision/mappings` 含 `YOLO_RETAIL`（V61，约 13 条演示映射） |

有柜有货后：按下文采集标注 → 自训 `cabinet-skus-v1.0.0.pt` → 切 `YOLO_SKU`，才进入真钱门禁。

---

## 1. 数据集与标注

见 [`vision-service/datasets/README.md`](../vision-service/datasets/README.md)。

类名与训练配置一致（`vision-service/training/data.yaml`）：

| class_name | SKU |
|------------|-----|
| `cola_330ml` | SKU-DEMO-001 |
| `sprite_500ml` | SKU-SODA-001 |
| `water_550ml` | SKU-WATER-001 |
| `chips_70g` | SKU-SNACK-001 |
| `milk_250ml` | SKU-MILK-001 |
| `noodle_bowl` | SKU-NOODLE-001 |

DB 种子：Flyway `V58__sku_vision_mapping_yolo_sku.sql`；运营后台「视觉映射」可继续维护。

---

## 2. 训练与导出

```powershell
cd ai-cabinet/vision-service
pip install -r requirements-ml.txt

# 标注完成后
python training/train_sku_yolo.py --epochs 80 --version cabinet-skus-v1.0.0

# 或仅导出现有 best.pt
python training/train_sku_yolo.py --export-only runs/detect/cabinet-skus-v1/weights/best.pt --version cabinet-skus-v1.0.0
```

产物：

- `vision-service/models/cabinet-skus-v1.0.0.pt`
- `vision-service/models/cabinet-skus-v1.0.0.manifest.json`（SHA256 + 类列表）

---

## 3. 生产环境变量

复制并编辑 `infra/.env.production.example` → `.env.production`：

```bash
VISION_MOCK_ENABLED=false
AICABINET_MOCK_ENABLED=false
RECOGNIZER_BACKEND=yolo
YOLO_RECOGNITION_MODE=delta
YOLO_MODEL_PATH=/app/models/cabinet-skus-v1.0.0.pt
YOLO_MODEL_VERSION=cabinet-skus-v1.0.0
YOLO_AUTO_DOWNLOAD=false
YOLO_CONF=0.45
YOLO_REVIEW_CONF=0.72
VISION_SKU_MODEL_FILE=cabinet-skus-v1.0.0.pt
VISION_MODEL_HOST_PATH=./models/production
```

门禁脚本：

```powershell
.\scripts\check-env.ps1 -CheckEnv -Prod
.\scripts\verify-vision-model.ps1 -RequireSkuMappings
.\scripts\deploy-production.ps1
```

---

## 4. Docker 部署

### 方式 A：镜像烘焙

```powershell
# 1. 将 cabinet-skus-v1.0.0.pt 放入 vision-service/models/
# 2. 构建
cd infra
docker compose -p ai-cabinet `
  -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.production.yml `
  --env-file .env.production --profile apps build vision-service

# 3. 全栈启动
docker compose -p ai-cabinet `
  -f docker-compose.yml -f docker-compose.apps.yml -f docker-compose.production.yml `
  --env-file .env.production --profile apps up -d
```

### 方式 B：运行时挂载

```powershell
mkdir infra\models\production
copy vision-service\models\cabinet-skus-v1.0.0.pt infra\models\production\
# VISION_MODEL_HOST_PATH 指向该目录（production compose 默认）
```

---

## 5. 验收

| 检查 | 命令 / 期望 |
|------|-------------|
| Health | `GET :8082/health` → `recognizer_available=true`, `mock_enabled=false` |
| 模型版本 | `model_version=cabinet-skus-v1.0.0`（非 `yolov8n`） |
| 映射 | `YOLO_SKU` ≥1 条；真实扣款前停用 `YOLO_COCO` |
| 灰度门禁 | `.\scripts\phase-f-gray-launch.ps1` 17/17 |
| §10 准确率 | ≥1000 次真机取放；记录漏识别/误识别率 |

上传识别抽检：

```powershell
.\scripts\verify-vision-model.ps1
# 可选：用 testdata/bottle.jpg + 运营映射回归
```

---

## 6. 灰度 / 回滚

1. 新版本命名 `cabinet-skus-v1.1.0.pt`，更新 `.env.production` 中 `YOLO_MODEL_*`
2. `docker compose ... up -d --no-deps vision-service`
3. 连续 14 天观察争议率、识别失败异常、资金对账
4. 回滚：恢复上一版本 `.pt` + manifest SHA256 校验

---

## 7. 相关文件

| 文件 | 说明 |
|------|------|
| `infra/docker-compose.vision-local.yml` | Phase 1 Retail-OS 冷启动 Compose 叠加 |
| `infra/.env.vision-dev` | 宿主机本地 YOLO 环境 |
| `infra/docker-compose.production.yml` | 生产 Compose 叠加 |
| `infra/.env.production.example` | 生产 env 模板 |
| `infra/docker/vision-service.Dockerfile` | ML + SKU 模型烘焙 |
| `scripts/download-retail-os-model.ps1` | Retail-OS 权重下载 |
| `scripts/verify-vision-model.ps1` | 模型/映射门禁 |
| `scripts/package-vision-model.ps1` | 复制权重到部署目录 |
| `docs/PRODUCTION.md` | 全栈生产指南 |
