package com.aicabinet.edge.vision

/**
 * 柜机端本地视觉配置（RKNN / 弱网策略）。
 */
object EdgeVisionConfig {
    /** 是否启用边缘推理（false 时始终走 vision-service） */
    const val EDGE_VISION_ENABLED = false

    /** RKNN 模型路径（柜机本地 storage） */
    const val RKNN_MODEL_PATH = "/data/aicabinet/models/cabinet-skus-v1.0.0.rknn"

    /** delta | single_frame，与 vision-service YOLO_RECOGNITION_MODE 对齐 */
    const val RECOGNITION_MODE = "delta"

    /** 弱网：有网且 YOLO 存疑时才调 DeepSeek（毫秒） */
    const val DEEPSEEK_TIMEOUT_MS = 2000L

    /** 关门后本地推理最大等待（毫秒） */
    const val LOCAL_INFERENCE_DEADLINE_MS = 3000L

    /** 网络不可用时的策略：LOCAL_ONLY | SKIP_CLOUD */
    const val OFFLINE_POLICY = "LOCAL_ONLY"
}
