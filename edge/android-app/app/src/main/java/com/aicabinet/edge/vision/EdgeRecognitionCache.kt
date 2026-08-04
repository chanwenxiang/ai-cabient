package com.aicabinet.edge.vision

import android.util.Log
import java.util.LinkedHashMap

/**
 * 边缘识别结果 LRU 缓存。
 * 避免同一会话的重复推理，并缓存最近 N 个会话的结果用于对比。
 */
class EdgeRecognitionCache(private val maxEntries: Int = 50) {

    companion object {
        private const val TAG = "EdgeRecogCache"
    }

    data class CachedSession(
        val sessionId: String,
        val deviceId: String,
        val openFrameDetections: List<NcnnYoloDetector.Detection>?,
        val closeFrameDetections: List<NcnnYoloDetector.Detection>?,
        val deltaResult: SkuDeltaCalculator.DeltaResult?,
        val timestamp: Long = System.currentTimeMillis()
    )

    // LRU: accessOrder=true 保证最近使用的在最前
    private val cache = object : LinkedHashMap<String, CachedSession>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSession>): Boolean {
            val shouldRemove = size > maxEntries
            if (shouldRemove) {
                Log.d(TAG, "evict oldest session: ${eldest.key}")
            }
            return shouldRemove
        }
    }

    @Synchronized
    fun putOpenDetections(sessionId: String, deviceId: String, detections: List<NcnnYoloDetector.Detection>) {
        val existing = cache[sessionId]
        if (existing != null) {
            cache[sessionId] = existing.copy(openFrameDetections = detections)
        } else {
            cache[sessionId] = CachedSession(sessionId, deviceId, detections, null, null)
        }
        Log.d(TAG, "cached open detections session=$sessionId items=${detections.size}")
    }

    @Synchronized
    fun putCloseDetections(sessionId: String, detections: List<NcnnYoloDetector.Detection>) {
        val existing = cache[sessionId] ?: run {
            Log.w(TAG, "close without open session=$sessionId")
            return
        }
        val delta = SkuDeltaCalculator.computeDelta(
            existing.openFrameDetections ?: emptyList(),
            detections
        )
        cache[sessionId] = existing.copy(closeFrameDetections = detections, deltaResult = delta)
        Log.d(TAG, "delta computed session=$sessionId removed=${delta.totalRemoved} review=${delta.needReview}")
    }

    @Synchronized
    fun getCached(sessionId: String): CachedSession? = cache[sessionId]

    @Synchronized
    fun getDeltaResult(sessionId: String): SkuDeltaCalculator.DeltaResult? = cache[sessionId]?.deltaResult

    @Synchronized
    fun getOpenDetections(sessionId: String): List<NcnnYoloDetector.Detection>? = cache[sessionId]?.openFrameDetections

    @Synchronized
    fun remove(sessionId: String) {
        cache.remove(sessionId)
    }

    @Synchronized
    fun clear() {
        cache.clear()
        Log.i(TAG, "cache cleared")
    }

    @Synchronized
    fun size(): Int = cache.size

    @Synchronized
    fun snapshot(): List<CachedSession> = cache.values.toList()
}
