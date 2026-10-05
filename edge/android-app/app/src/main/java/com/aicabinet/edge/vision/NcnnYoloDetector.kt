package com.aicabinet.edge.vision

import android.graphics.Bitmap
import java.io.File

/**
 * NCNN YOLO 推理。模型文件不存在时走 Mock，保证 CI mockDebug 可编译可测。
 */
class NcnnYoloDetector(modelPath: String) {
    val useMock: Boolean = !File(modelPath).exists()
    val available: Boolean = true
    val modelVersion: String = if (useMock) "mock-yolo" else "cabinet-skus-v1.0.0"

    data class Detection(
        val label: String,
        val skuLabel: String = label,
        val confidence: Float,
        val delta: Int = 1,
        val avgConfidence: Float = confidence,
    )

    fun detect(frame: Bitmap): List<Detection> {
        if (frame.width <= 0 || frame.height <= 0) return emptyList()
        if (!useMock) return emptyList()
        return listOf(
            Detection(label = "SKU-DEMO-001", confidence = 0.92f),
        )
    }

    fun release() {
        // native 句柄在真实 so 接入后释放
    }
}
