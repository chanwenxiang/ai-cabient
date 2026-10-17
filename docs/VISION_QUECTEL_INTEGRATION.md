# 移远 OpenVending 视觉对接方案

> 状态：识别现役=**将邑端侧单轨**（已落地，见 §3/§5 阶段 A）；移远 OpenVending 为**备选端侧提供方**，SDK/硬件联调未完成。

## 1. 背景与目标

视觉链路为端侧识别：柜机摄像头 → **端侧 AI 提供方**推理 → SKU 清单上报 trade-service 结算。

云端识别引擎 `vision-service`（旧 `:8082`、`RECOGNIZER_BACKEND`、`VISION_API_KEY`）已于 2026-10-11 整体退役
（判定文档 [`JIANGYI_ONLY_FINAL_VERDICT_2026-10-11.md`](JIANGYI_ONLY_FINAL_VERDICT_2026-10-11.md) §3 批 3）：
平台**没有任何云端识别**，识别结果只有两个入口 —— 将邑 `jiangyi-gateway` 归一化上报，和第三方端侧直报
`POST /internal/v1/vision/edge-results`。本地/CI 联调用 `device-simulator` + `AICABINET_MOCK_ENABLED=true`，
不再有「云端 mock 识别」。

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
| `POST /internal/v1/vision/edge-results` | 第三方端侧识别结果直报（契约 `VisionRecognitionResultDto`）：需 `deviceId` 归属校验 + `modelVersion` 必填；未命中映射/低置信 fail-closed 转 DISPUTED |
| 异常中心字典 | `exception_type.VISION_ANOMALY = 视觉异常（端侧）`，管理端「异常中心」直接可见、可领取/转派/处置 |

## 4. 事件类型约定

| eventType | 中文 | 严重度 | 说明 |
|-----------|------|--------|------|
| `ITEM_MISPLACE` | 商品错拿 | MEDIUM | 拿取后未结算/放回异常 |
| `OCCLUSION` | 遮挡识别 | MEDIUM | 手部/异物遮挡导致识别受阻 |
| `TAMPER` | 防撬告警 | HIGH | 柜门/锁体被撬 |
| `ABNORMAL_OPEN` | 异常开门 | HIGH | 非授权/非会话开门 |

## 5. 后续阶段（依赖移远资料）

### 阶段 A：端侧识别结果接入结算（将邑已落地，移远待接）
- 移远侧仍需确认上报协议（items 数量变化、置信度、模型版本、need_review）——平台契约已冻结为
  `VisionRecognitionResultDto`，将邑经 `jiangyi-gateway` 归一化后复用同一入口；
- `POST /internal/v1/vision/edge-results`（已落地）：置信度达标直接结算，未达标/未命中映射
  **fail-closed 转争议 + 人工复核**（云端复核已随识别引擎退役，不存在）；
- 端侧 SKU 清单沿用 class→SKU 映射：管理端「识别映射」页 `/vision-mappings`
  → `jiangyi_class_mapping`（须 `ACTIVE`，`modelVersion` 含 mock/fallback 时禁止静默扣款）。

### 阶段 B：设备接入与运维
- 边缘盒/摄像头在线监控（并入设备可用性 KPI）；
- 识别准确率报表（**不做云端识别** —— 真值取自人工复核结论：争议结案 `KEEP`=识别正确、
  `WAIVE`/`ADJUST`/`CONFIRM`=识别有误，见 `cabinet.recognition.human_verdict` 指标）；
- 异常事件一键生成维修工单。

### 阶段 C：试点与参数
- 移远试点柜与将邑柜灰度并行；
- 配置化阈值：置信度门槛、事件上报频率、告警通道。

## 6. 需要的移远信息

- OpenVending 方案书与报价（端侧套件清单、每台成本）；
- 端侧结果/异常事件的对接协议（MQTT topic 或 HTTP API、字段说明、鉴权）；
- 端侧 SDK 或参考实现（鉴权、请求签名、响应解析）；
- 试点环境与样机排期。
