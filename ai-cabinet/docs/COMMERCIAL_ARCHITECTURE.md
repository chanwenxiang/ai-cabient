# 商业落地架构（非自研 CV）

本文描述在**不自研深度学习模型**前提下，将 AI 开门柜从 demo 推向商业试点的推荐架构，与当前代码实现对齐。

---

## 1. 总体架构

```text
小程序 → trade-service → device-service → MQTT → 柜机
                              ↓
                         关门 videoUri
                              ↓
                    vision-service（识别）
                              ↓
              ┌───────────────┴───────────────┐
              │  dev: YOLO（本地联调）         │
              │  prod: 阿里云商品理解 + 映射表  │
              └───────────────┬───────────────┘
                              ↓
                    SKU 清单 → 自动扣款 / 争议审核
```

对象存储：

| 环境 | 存储 | URI 示例 |
|------|------|----------|
| 本地 dev | MinIO（Docker） | `minio://cabinet-videos/sim/xxx.jpg` |
| 生产 prod | 阿里云 OSS（S3 兼容） | `oss://your-bucket/sessions/xxx.jpg` |

同一套 MinIO SDK / 配置项，生产仅改 `MINIO_ENDPOINT` 为 OSS 地址。

---

## 2. 识别后端（vision-service）

环境变量 `RECOGNIZER_BACKEND`：

| 值 | 用途 |
|----|------|
| `yolo` | 本地开发、演示（默认） |
| `aliyun` | 生产：仅阿里云 ClassifyCommodity |
| `hybrid` | **推荐生产**：阿里云优先，失败回退 YOLO |

### 阿里云商品理解

- 产品：[视觉智能开放平台 - 商品理解](https://help.aliyun.com/zh/viapi/developer-reference/understand-the-goods-1/)
- API：`ClassifyCommodity`（类目 ID + 置信度）
- 输入：图片 **URL**（推荐 OSS 同区域预签名链接）
- 输出：电商类目 → 通过 `aliyun_category_mapping` 表映射为 SKU

### SKU 映射（trade-service）

| 表 | 来源 | 说明 |
|----|------|------|
| `sku_vision_mapping` | YOLO COCO 类名 | 本地 dev |
| `aliyun_category_mapping` | 阿里云类目 ID | 生产 |

vision-service 通过内部接口拉取映射（可缓存）：

```http
GET /internal/v1/vision/mappings
X-Internal-Api-Key: ...
```

---

## 3. 环境变量速查

### 本地开发（不变）

```bash
RECOGNIZER_BACKEND=yolo
MOCK_ENABLED=true
MINIO_ENDPOINT=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
OBJECT_STORAGE_SCHEME=minio
```

### 生产试点

```bash
# vision-service
RECOGNIZER_BACKEND=hybrid
MOCK_ENABLED=false
VISION_API_KEY=<强密钥>
INTERNAL_API_KEY=<与 trade 相同>

# 对象存储 → 阿里云 OSS（S3 兼容 endpoint）
MINIO_ENDPOINT=https://oss-cn-shanghai.aliyuncs.com
MINIO_ACCESS_KEY=<OSS AccessKeyId>
MINIO_SECRET_KEY=<OSS AccessKeySecret>
MINIO_BUCKET=cabinet-videos
OSS_REGION=cn-shanghai
OBJECT_STORAGE_SCHEME=oss

# 阿里云商品理解
ALIBABA_CLOUD_ACCESS_KEY_ID=...
ALIBABA_CLOUD_ACCESS_KEY_SECRET=...
ALIYUN_GOODSTECH_ENDPOINT=goodstech.cn-shanghai.aliyuncs.com
ALIYUN_REVIEW_CONF=0.7

TRADE_SERVICE_URL=http://trade-service:8080
```

柜机 / 模拟器上报：`oss://cabinet-videos/sessions/{sessionId}.jpg`

---

## 4. 上线步骤（非自研 CV）

1. 开通阿里云 OSS、视觉智能「商品理解」
2. 创建 bucket（与 vision 同区域，如上海）
3. 用真实柜内商品图调用 `ClassifyCommodity`，记录返回的 `categoryId`
4. 在 DB `aliyun_category_mapping` 配置类目 → SKU
5. 生产部署 `RECOGNIZER_BACKEND=hybrid`，`MOCK_ENABLED=false`
6. 端到端：关门 → OSS → 阿里云识别 → 扣款；低置信度走争议审核

---

## 5. 与自研 CV 的边界

| 能力 | 本方案 | 自研 CV（未做） |
|------|--------|----------------|
| 具体包装/品牌 | 依赖阿里云类目粒度 + 运营映射 | 自训模型 |
| 上线速度 | 快 | 慢 |
| 运营成本 | API 按量 + 人工审核 | 算法团队 + 标注 |
| 本地联调 | YOLO + MinIO | 同 |

争议审核、支付、MQTT、运营后台为商业标配，已保留。

---

## 6. 相关文档

- [PRODUCTION.md](PRODUCTION.md) — 生产环境变量
- [OPS_COMMERCIAL.md](OPS_COMMERCIAL.md) — OTA / 风控 / 对账 / 补货 / SLA / RBAC
- [LOCAL_SETUP.md](LOCAL_SETUP.md) — 本地联调
- [VISION_YOLO_TEST.md](VISION_YOLO_TEST.md) — YOLO 本地测试
