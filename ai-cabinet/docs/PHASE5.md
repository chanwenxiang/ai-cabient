# Phase 5 指南

## 新增能力

| 模块 | 内容 |
|------|------|
| vision-service | `minio://` 视频下载缓存 + YOLO/mock 识别 |
| 运营 Web 后台 | `http://localhost:8080/admin/index.html` |
| 数据迁移 | `migration/scripts/` ETL SQL |
| Ops API | `GET /api/v2/ops/skus` 商品目录 |

---

## MinIO 视频全链路

```
关门 MQTT videoUri=minio://bucket/key
  → vision-service 从 MinIO 下载到 cache/videos/
  → YOLO 推理（或 mock）
  → trade-service 结算 / 争议
```

### 环境变量

| 变量 | 默认 | 说明 |
|------|------|------|
| `MINIO_ENDPOINT` | `http://localhost:9000` | MinIO 地址 |
| `MINIO_ACCESS_KEY` | `minioadmin` | Access Key |
| `MINIO_SECRET_KEY` | `minioadmin` | Secret Key |
| `VIDEO_CACHE_DIR` | `cache/videos` | 本地缓存目录 |
| `YOLO_MODEL_PATH` | `models/yolov8n.pt` | YOLO 模型路径 |

### 启动

```powershell
cd ai-cabinet/vision-service
pip install -r requirements.txt

# 可选 YOLO
pip install ultralytics opencv-python-headless

$env:MINIO_ENDPOINT = "http://localhost:9000"
uvicorn app.main:app --port 8082
```

### 创建 bucket 与测试视频

```powershell
# MinIO 控制台 http://localhost:9001  minioadmin/minioadmin
# 创建 bucket: cabinet-videos

# 上传占位视频供模拟器 URI 使用
mc alias set local http://localhost:9000 minioadmin minioadmin
mc cp test.mp4 local/cabinet-videos/sim/SESSION_ID.mp4
```

模拟器关门 URI：`minio://cabinet-videos/sim/{sessionId}.mp4`

- 文件存在 + mock 模式 → 置信度 0.92，自动结算
- 文件不存在 → `need_review=true`，生成争议工单

---

## 运营 Web 后台

浏览器打开：

```
http://localhost:8080/admin/index.html
```

1. 运营账号登录（默认 `13900000001` / `123456`）
2. 查看 OPEN 争议工单
3. 选择 SKU + 数量 → 审核结案

与小程序 `pages/disputes` 共用同一套 API：

- `GET /api/v2/ops/disputes`
- `POST /api/v2/ops/disputes/{ticketId}/resolve`
- `GET /api/v2/ops/skus`

---

## 数据迁移（ego-automat → ai-cabinet）

详见 `migration/README.md`。

```powershell
# 1. 旧库 MySQL 导出
mysql -u root -p ego_automat -N -B -e "source migration/scripts/export_users.sql" > users.tsv

# 2. 转为 CSV 后导入 PostgreSQL staging 表
psql -U aicabinet -d aicabinet -f migration/scripts/import_to_aicabinet.sql
```

映射要点：

| 旧 | 新 |
|----|-----|
| `m8_user_info` + `m8_user_account` | `user_info` + `user_account` |
| `ego_machine_base_info.machine_code` | `device_info.device_id` |
| `ego_goods_sku_info` | `sku_catalog`（`SKU-{id}`） |

`device_info.capabilities` 保留 `legacyMachineId` 便于对照。

---

## 启动步骤（完整）

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
cd ai-cabinet/infra && docker compose up -d
cd .. && mvn install -DskipTests

# 终端 1
cd vision-service && uvicorn app.main:app --port 8082

# 终端 2
cd services/device-service && mvn spring-boot:run

# 终端 3
cd services/trade-service && mvn spring-boot:run

# 终端 4（可选）
cd edge/device-simulator && mvn exec:java -Dexec.args="CAB-001"
```

---

## Phase 6 计划（可选）

- API Gateway（Spring Cloud Gateway / Nginx）
- Kafka 异步识别任务
- 生产 K8s Helm Chart
- 争议工单视频预览（MinIO 预签名 URL）
