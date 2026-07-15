package com.aicabinet.edge.vision

import android.graphics.Bitmap
import android.util.Log
import java.io.File

/**
 * NCNN YOLOv8 推理引擎。
 * 开发环境使用 Mock 模式（模拟检测结果）；
 * 生产环境替换 NCNN 原生 so 库即可切换到真实推理。
 */
class NcnnYoloDetector(
    private val modelPath: String,
    private val useMock: Boolean = !File(modelPath).exists()
) {
    companion object {
        private const val TAG = "NcnnDetector"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.45f
        private const val NMS_THRESHOLD = 0.5f
        private const val MOCK_CLASSES = 80  // COCO classes
    }

    data class Detection(
        val label: String,
        val confidence: Float,
        val x: Float, val y: Float,
        val width: Float, val height: Float
    )

    private var nativeLoaded = false
    private var modelLoaded = false
    private var loadError: String? = null

    val available: Boolean get() = modelLoaded || useMock
    val modelVersion: String get() = if (useMock) "mock-v1" else "ncnn-yolov8-v1"

    init {
        if (!useMock) {
            try {
                System.loadLibrary("ncnn")
                System.loadLibrary("ncnnyolo")
                nativeLoaded = true
                modelLoaded = initModel(modelPath, INPUT_SIZE, CONFIDENCE_THRESHOLD, NMS_THRESHOLD) >= 0
                Log.i(TAG, "NCNN model loaded: $modelPath, native=$nativeLoaded model=$modelLoaded")
            } catch (e: UnsatisfiedLinkError) {
                loadError = "NCNN native libs not found, fallback to mock"
                Log.w(TAG, loadError!!)
            }
        } else {
            Log.i(TAG, "NCNN mock mode enabled (model not found at $modelPath)")
        }
    }

    /**
     * 对输入 Bitmap 执行目标检测。
     * 生产环境：通过 JNI 调用 NCNN 推理；
     * Mock 环境：返回模拟检测结果。
     */
    fun detect(bitmap: Bitmap): List<Detection> {
        if (useMock || !nativeLoaded) {
            return mockDetect(bitmap)
        }
        if (!modelLoaded) {
            Log.w(TAG, "model not loaded, returning empty")
            return emptyList()
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        val result = nativeDetect(pixels, bitmap.width, bitmap.height)
        val boxes = result?.filter { it.confidence >= CONFIDENCE_THRESHOLD }?.sortedByDescending { it.confidence }
        return boxes ?: emptyList()
    }

    /**
     * Mock 检测：在图像中央区域生成 1-3 个模拟检测框。
     * 开发时便于测试管线连通性。
     */
    private fun mockDetect(bitmap: Bitmap): List<Detection> {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val mockItems = listOf(
            Detection("bottle", 0.82f, w * 0.2f, h * 0.3f, w * 0.15f, h * 0.3f),
            Detection("bottle", 0.75f, w * 0.6f, h * 0.4f, w * 0.12f, h * 0.28f),
            Detection("cup", 0.65f, w * 0.4f, h * 0.6f, w * 0.1f, h * 0.15f),
        )
        Log.d(TAG, "mock detect: ${mockItems.size} items on ${w.toInt()}x${h.toInt()}")
        return mockItems
    }

    fun release() {
        if (nativeLoaded && modelLoaded) {
            nativeRelease()
        }
        modelLoaded = false
    }

    // ── JNI 接口（由 NCNN 原生 so 实现） ──

    private external fun initModel(modelPath: String, inputSize: Int,
                                   confThresh: Float, nmsThresh: Float): Int
    private external fun nativeDetect(pixels: IntArray, width: Int, height: Int): List<Detection>?
    private external fun nativeRelease()
}
