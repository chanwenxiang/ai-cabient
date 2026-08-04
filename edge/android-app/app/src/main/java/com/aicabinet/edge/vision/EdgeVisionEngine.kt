package com.aicabinet.edge.vision

import android.graphics.Bitmap
import android.util.Log
import java.io.File

class EdgeVisionEngine(private val context: android.content.Context) {

    companion object {
        private const val TAG = "EdgeVisionEngine"
    }

    data class EdgeLineItem(
        val skuId: String,
        val quantity: Int,
        val confidence: Float,
    )

    data class EdgeRecognitionResult(
        val items: List<EdgeLineItem>,
        val needReview: Boolean,
        val modelVersion: String,
        val detectedClasses: List<String> = emptyList(),
        val source: String = "EDGE_YOLO",
    )

    private val detector: NcnnYoloDetector
    private val cache = EdgeRecognitionCache()
    private var currentSessionId: String? = null

    init {
        val useEdge = EdgeVisionConfig.EDGE_VISION_ENABLED
        detector = NcnnYoloDetector(EdgeVisionConfig.RKNN_MODEL_PATH)
        if (useEdge && detector.available) {
            Log.i(TAG, "Edge inference initialized model=${detector.modelVersion} mock=${!File(EdgeVisionConfig.RKNN_MODEL_PATH).exists()}")
        } else {
            Log.i(TAG, "Edge disabled (useEdge=$useEdge available=${detector.available})")
        }
    }

    /** 处理开门帧。由 CabinetController 在开门时调用。 */
    fun processOpenFrame(sessionId: String, deviceId: String, frame: Bitmap) {
        if (!isActive()) return
        currentSessionId = sessionId
        val detections = detector.detect(frame)
        cache.putOpenDetections(sessionId, deviceId, detections)
        Log.i(TAG, "open frame session=$sessionId detections=${detections.size}")
    }

    /** 处理关门帧。由 CabinetController 在关门后调用。 */
    fun processCloseFrame(sessionId: String, frame: Bitmap) {
        if (!isActive()) return
        if (sessionId != currentSessionId) {
            Log.w(TAG, "session mismatch current=$currentSessionId got=$sessionId")
        }
        val detections = detector.detect(frame)
        cache.putCloseDetections(sessionId, detections)
        Log.i(TAG, "close frame session=$sessionId detections=${detections.size}")
    }

    /** 获取本地推理结果。null 表示未启用或无需本地处理。 */
    fun getResult(sessionId: String, networkAvailable: Boolean = true): EdgeRecognitionResult? {
        if (!isActive()) return null
        val delta = cache.getDeltaResult(sessionId) ?: return null
        val cached = cache.getCached(sessionId) ?: return null

        val items = delta.items.map { d ->
            EdgeLineItem(d.skuLabel, d.delta.coerceAtLeast(0), d.avgConfidence)
        }
        val classes = (cached.openFrameDetections.orEmpty() + cached.closeFrameDetections.orEmpty())
            .map { it.label }.distinct()

        val skippedCloud = !delta.needReview && !networkAvailable
        val result = EdgeRecognitionResult(
            items = items,
            needReview = delta.needReview && !skippedCloud,
            modelVersion = detector.modelVersion,
            detectedClasses = classes,
            source = if (delta.needReview) "EDGE_DELTA_LOW_CONF" else "EDGE_DELTA"
        )
        Log.i(TAG, "edge result session=$sessionId items=${items.size} review=${result.needReview}")
        return result
    }

    /** 弱网策略：本地结果足够置信则跳过云端 vision 调用。 */
    fun shouldSkipCloudRecognition(result: EdgeRecognitionResult?): Boolean {
        if (result == null) return false
        return result.items.isNotEmpty() && !result.needReview
    }

    /** 存疑且联网时可请求 DeepSeek 兜底。 */
    fun shouldRequestDeepSeekFallback(result: EdgeRecognitionResult?, networkAvailable: Boolean): Boolean {
        if (!networkAvailable) return false
        return result != null && (result.items.isEmpty() || result.needReview)
    }

    fun isActive(): Boolean = EdgeVisionConfig.EDGE_VISION_ENABLED && detector.available
    fun discardSession(sid: String) { cache.remove(sid); if (currentSessionId == sid) currentSessionId = null }
    fun clearCache() { cache.clear(); currentSessionId = null }
    fun release() { detector.release(); cache.clear() }
    fun stats(): Map<String, Any> = mapOf(
        "active" to isActive(), "modelVersion" to detector.modelVersion,
        "cachedSessions" to cache.size(), "useMock" to !File(EdgeVisionConfig.RKNN_MODEL_PATH).exists())
}
