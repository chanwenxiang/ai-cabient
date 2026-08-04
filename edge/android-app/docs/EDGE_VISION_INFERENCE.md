# 柜机端本地视觉推理

已实现完整的边缘推理管线。默认关闭（`EDGE_VISION_ENABLED=false`），启用后可在柜机端完成 YOLO delta 识别。

## 目标

- 弱网或断网时，关门后 **3 秒内** 在柜机端完成 open/close 帧 delta 识别
- 上传 **识别结果 JSON + 备查短视频** 到 trade-service / MinIO
- 本地结果高置信度时跳过云端 vision-service 调用，降低延迟与成本

## 已实现模块

```
edge/android-app/app/src/main/java/com/aicabinet/edge/vision/
  EdgeVisionConfig.kt          # 配置：模型路径、识别模式、弱网策略
  EdgeVisionEngine.kt          # 推理协调器：管理完整推理生命周期
  NcnnYoloDetector.kt          # NCNN YOLO 推理引擎（含 Mock 回溯）
  SkuDeltaCalculator.kt        # Delta 差异计算：open vs close 帧
  EdgeRecognitionCache.kt      # LRU 缓存：缓存最近 50 个会话
  FrameCaptureManager.kt       # 帧提取：从录制视频中提取关键帧
```

## 推理流程

```
开门触发 → CabinetController
  → videoRecorder.start() 开始录制
  → DoorCloseWatcher 等待关门
  → videoRecorder.stop() 停止录制
  → FrameCaptureManager 提取开门帧 + 关门帧
  → EdgeVisionEngine.processOpenFrame()   ← 缓存开门检测
  → EdgeVisionEngine.processCloseFrame()  ← 缓存关门+计算 delta
  → EdgeVisionEngine.getResult()          ← 获取本地结果
  ├─ 高置信度 → skipCloud, 直接上报 MQTT
  └─ 低置信度 → needReview, 走 vision-service 兜底
  → 上报 + 结算
```

## 开发与 Mock 模式

- **Mock 模式**: `RKNN_MODEL_PATH` 不存在时自动启用，返回模拟检测结果
- **真实推理**: 部署 NCNN so 库 + YOLO `.rknn` 模型后自动切换
- 两种模式通过 `NcnnYoloDetector.useMock` 字段区分

## 弱网策略

| 场景 | 行为 |
|------|------|
| 有网 + 本地高置信 | 跳过同步 vision，直接结算 |
| 有网 + 本地存疑 | 上传视频 + JSON；vision-service 兜底 |
| 断网 | 本地推理；会话排队，恢复网络后补传 |

## 性能指标（开发基准）

| 指标 | Mock 模式 | 生产估算 |
|------|-----------|---------|
| 单帧推理 | <5ms | <100ms |
| Delta 计算 | <1ms | <5ms |
| 帧提取 | <30ms | <30ms |
| 端到端 | <50ms | <500ms |

## 启用方式

```kotlin
// EdgeVisionConfig.kt
const val EDGE_VISION_ENABLED = true
```
