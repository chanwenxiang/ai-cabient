# 移远 OpenVending 视觉对接方案

> 状态：规划中（已预留接入点，未完成 SDK/硬件联调）

## 1. 背景与目标

现视觉链路规划为端侧识别：柜机摄像头 → **端侧 AI 提供方**推理 → SKU 清单上报 trade-service 结算。

开发联调仍可用云端 vision-service mock（`MOCK_ENABLED=true`）。

计划对接移远 OpenVending（端侧 AI：摄像头 + AI 算力模组/边缘盒），目标：

1. 识别下沉到端侧，结算更快、更省云端算力；
2. 端侧异常行为事件（错拿 / 遮挡 / 防撬 / 异常开门）实时上报，补齐运营闭环；
3. 云端不再维护自研 YOLO；低置信度进争议/人工复核。

## 2. 目标架构

```text
移远摄像头/边缘盒 ── 端侧识别结果 + 异常事件 ──► device-service / trade-service（内部 API）
        │                                                  │
        └── 低置信度 ──► 争议/人工复核 ─────────────────────┘
                                                          │
                                    异常中心(VISION_ANOMALY) + 钉钉/企微告警
```

## 3. 已落地接入点（不依赖移远 SDK）

| 接入点 | 说明 |
|--------|------|
| `POST /internal/v1/vision/anomaly-events` | 端侧异常事件批量上报：错拿/遮挡/防撬/异常开门 → 异常中心 `VISION_ANOMALY`；高危事件（防撬、异常开门）自动推送钉钉/企业微信/通用 Webhook |
| `VisionAnomalyEventDto` | 事件契约：deviceId、sessionId、eventType、confidence、detail、provider、occurredAt |
| vision-service `RECOGNIZER_BACKEND=quectel` | 提供商占位（`app/recognition/quectel_recognizer.py`），SDK 就绪前显式不可用，避免静默降级 |
| 异常中心字典 | `exception_type.VISION_ANOMALY = 视觉异常（端侧）`，管理端「异常中心」直接可见、可领取/转派/处置 |

## 4. 事件类型约定

| eventType | 中文 | 严重度 | 说明 |
|-----------|------|--------|------|
| `ITEM_MISPLACE` | 商品错拿 | MEDIUM | 拿取后未结算/放回异常 |
| `OCCLUSION` | 遮挡识别 | MEDIUM | 手部/异物遮挡导致识别受阻 |
| `TAMPER` | 防撬告警 | HIGH | 柜门/锁体被撬 |
| `ABNORMAL_OPEN` | 异常开门 | HIGH | 非授权/非会话开门 |

## 5. 后续阶段（依赖移远资料）

### 阶段 A：端侧识别结果接入结算
- 约定移远识别结果上报协议（items 数量变化、置信度、模型版本、need_review）；
- 新增 `POST /internal/v1/vision/edge-results`：置信度达标直接结算，未达标转云端复核；
- 端侧 SKU 清单沿用现有 class→SKU 映射（`/api/v2/ops/admin/vision-mappings`）。

### 阶段 B：设备接入与运维
- 边缘盒/摄像头在线监控（并入设备可用性 KPI）；
- 识别准确率报表（端侧 vs 云端复核对比）；
- 异常事件一键生成维修工单。

### 阶段 C：试点与参数
- 移远试点柜与云端柜灰度并行；
- 配置化阈值：置信度门槛、事件上报频率、告警通道。

## 6. 需要的移远信息

- OpenVending 方案书与报价（端侧套件清单、每台成本）；
- 端侧结果/异常事件的对接协议（MQTT topic 或 HTTP API、字段说明、鉴权）；
- 端侧 SDK 或参考实现（鉴权、请求签名、响应解析）；
- 试点环境与样机排期。
