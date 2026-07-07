package com.aicabinet.edge.upload

import android.content.Context
import android.util.Log
import com.aicabinet.edge.video.VideoClipJson
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class PendingUpload(
    val sessionId: String,
    val files: Map<String, String>,
    val fusionMode: String = "SINGLE",
    val enqueuedAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0
) {
    /** 兼容旧版单文件队列 */
    constructor(sessionId: String, localPath: String, enqueuedAt: Long, attempts: Int) : this(
        sessionId,
        mapOf("TOP" to localPath),
        "SINGLE",
        enqueuedAt,
        attempts
    )
}

/**
 * 断网续传：关门时 MinIO 不可达则 LOCAL_QUEUED，后台重试上传并通知 trade 结算。
 */
class OfflineUploadQueue(
    private val context: Context,
    private val minioUploader: MinioUploader = MinioUploader()
) {
    private val mapper = jacksonObjectMapper()
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "offline-upload").apply { isDaemon = true }
    }

    fun start() {
        executor.scheduleWithFixedDelay({ processQueue() }, 5, 30, TimeUnit.SECONDS)
        executor.execute { processQueue() }
    }

    fun stop() {
        executor.shutdownNow()
    }

    fun enqueue(sessionId: String, files: Map<String, File>, fusionMode: String) {
        val pending = loadQueue().filter { it.sessionId != sessionId } +
                PendingUpload(
                    sessionId,
                    files.mapValues { it.value.absolutePath },
                    fusionMode
                )
        saveQueue(pending)
        Log.i(TAG, "queued offline upload session=$sessionId files=${files.size} fusion=$fusionMode")
        executor.execute { processQueue() }
    }

    fun enqueueSingle(sessionId: String, localFile: File) {
        enqueue(sessionId, mapOf("TOP" to localFile), "SINGLE")
    }

    private fun processQueue() {
        val queue = loadQueue()
        if (queue.isEmpty()) return
        val remaining = mutableListOf<PendingUpload>()
        for (item in queue) {
            try {
                uploadPending(item)
                item.files.values.forEach { path -> File(path).delete() }
                Log.i(TAG, "offline upload completed session=${item.sessionId}")
            } catch (e: Exception) {
                Log.w(TAG, "offline upload retry session=${item.sessionId} attempt=${item.attempts + 1}: ${e.message}")
                if (item.attempts < MAX_ATTEMPTS) {
                    remaining.add(item.copy(attempts = item.attempts + 1))
                } else {
                    Log.e(TAG, "offline upload abandoned session=${item.sessionId}")
                }
            }
        }
        saveQueue(remaining)
    }

    private fun uploadPending(item: PendingUpload) {
        val uriPairs = item.files.map { (camera, path) ->
            val file = File(path)
            if (!file.exists()) throw IllegalStateException("missing file $path")
            camera to minioUploader.uploadVideoStrict(item.sessionId, file, camera)
        }
        val primaryUri = uriPairs.first { it.first == "TOP" }.second
        val clipsJson = if (item.fusionMode == "MULTI" && uriPairs.size >= 2) {
            VideoClipJson.build(uriPairs)
        } else {
            null
        }
        TradeVideoClient.attachVideo(item.sessionId, primaryUri, item.fusionMode, clipsJson)
    }

    private fun loadQueue(): List<PendingUpload> {
        val json = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        return runCatching { mapper.readValue<List<PendingUpload>>(json) }.getOrElse { emptyList() }
    }

    private fun saveQueue(items: List<PendingUpload>) {
        prefs.edit().putString(KEY_QUEUE, mapper.writeValueAsString(items)).apply()
    }

    companion object {
        private const val TAG = "OfflineUploadQueue"
        private const val PREFS = "offline_upload_queue"
        private const val KEY_QUEUE = "pending"
        private const val MAX_ATTEMPTS = 20
    }
}
