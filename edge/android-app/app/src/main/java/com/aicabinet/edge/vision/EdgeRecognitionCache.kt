package com.aicabinet.edge.vision

import java.util.LinkedHashMap

/** 最近会话的开门/关门检测缓存（上限 50）。 */
class EdgeRecognitionCache {
    data class CachedSession(
        val sessionId: String,
        val deviceId: String?,
        var openFrameDetections: List<NcnnYoloDetector.Detection>? = null,
        var closeFrameDetections: List<NcnnYoloDetector.Detection>? = null,
    )

    private val sessions = object : LinkedHashMap<String, CachedSession>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedSession>?): Boolean =
            size > 50
    }

    @Synchronized
    fun putOpenDetections(sessionId: String, deviceId: String, detections: List<NcnnYoloDetector.Detection>) {
        val cached = sessions.getOrPut(sessionId) { CachedSession(sessionId, deviceId) }
        cached.openFrameDetections = detections
    }

    @Synchronized
    fun putCloseDetections(sessionId: String, detections: List<NcnnYoloDetector.Detection>) {
        val cached = sessions.getOrPut(sessionId) { CachedSession(sessionId, null) }
        cached.closeFrameDetections = detections
    }

    @Synchronized
    fun getCached(sessionId: String): CachedSession? = sessions[sessionId]

    @Synchronized
    fun getDeltaResult(sessionId: String): SkuDeltaCalculator.DeltaResult? {
        val cached = sessions[sessionId] ?: return null
        val open = cached.openFrameDetections ?: return null
        val close = cached.closeFrameDetections ?: return null
        return SkuDeltaCalculator.diff(open, close)
    }

    @Synchronized
    fun remove(sessionId: String) {
        sessions.remove(sessionId)
    }

    @Synchronized
    fun clear() {
        sessions.clear()
    }

    @Synchronized
    fun size(): Int = sessions.size
}
