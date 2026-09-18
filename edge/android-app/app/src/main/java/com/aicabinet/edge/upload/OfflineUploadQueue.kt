package com.aicabinet.edge.upload

import android.content.Context
import android.util.Log
import com.aicabinet.edge.config.EdgeRuntimeConfig
import com.aicabinet.edge.queue.PrefsJsonQueue
import com.aicabinet.edge.video.VideoClipJson
import com.fasterxml.jackson.core.type.TypeReference
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class PendingUpload(
    val sessionId: String,
    val files: Map<String, String>,
    val fusionMode: String = "SINGLE",
    val deviceId: String = "",
    val userId: Long = 0L,
    val enqueuedAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    /** C22: 入队即已关门（离线补传的都是已结束会话），上传成功后据此补发 CLOSED 门事件 */
    val closed: Boolean = true
) {
    /** 兼容旧版单文件队列 */
    constructor(sessionId: String, localPath: String, enqueuedAt: Long, attempts: Int) : this(
        sessionId,
        mapOf("TOP" to localPath),
        "SINGLE",
        "",
        0L,
        enqueuedAt,
        attempts
    )
}

/**
 * 断网续传：关门时 MinIO 不可达则 LOCAL_QUEUED，后台重试上传并通知 trade 结算。
 * 持久化走 [PrefsJsonQueue]（与 MQTT 出站队列同存储策略）。
 */
class OfflineUploadQueue(
    private val context: Context,
    private val minioUploader: MinioUploader = MinioUploader(context)
) {
    private val store = PrefsJsonQueue(
        context = context,
        prefsName = PREFS,
        typeRef = object : TypeReference<List<PendingUpload>>() {},
        tag = TAG
    )
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "offline-upload").apply { isDaemon = true }
    }

    /** C22: 上传成功后补发关门事件的回调（CabinetController 注入 MqttDeviceClient）；
     *  MQTT 未连接时经内部 OutboundMqttQueue 持久化补投，trade 侧有幂等守卫。 */
    @Volatile
    var closedEventPublisher: ((sessionId: String, uploadStatus: String) -> Unit)? = null

    /** H62a: 队列放弃告警发布回调（发布失败仅日志，防循环） */
    @Volatile
    var alertPublisher: ((alertType: String, message: String) -> Unit)? = null

    fun start() {
        executor.scheduleWithFixedDelay({ processQueue() }, 5, 30, TimeUnit.SECONDS)
        executor.execute { processQueue() }
    }

    fun stop() {
        executor.shutdownNow()
    }

    fun enqueue(sessionId: String, files: Map<String, File>, fusionMode: String, deviceId: String, userId: Long) {
        store.mutate { pending ->
            pending.removeAll { it.sessionId == sessionId }
            pending.add(
                PendingUpload(
                    sessionId,
                    files.mapValues { it.value.absolutePath },
                    fusionMode,
                    deviceId,
                    userId
                )
            )
        }
        Log.i(TAG, "queued offline upload session=$sessionId files=${files.size} fusion=$fusionMode")
        executor.execute { processQueue() }
    }

    fun enqueueSingle(sessionId: String, localFile: File, deviceId: String = "", userId: Long = 0L) {
        enqueue(sessionId, mapOf("TOP" to localFile), "SINGLE", deviceId, userId)
    }

    private fun processQueue() {
        val queue = store.snapshot()
        if (queue.isEmpty()) return
        val remaining = mutableListOf<PendingUpload>()
        for (item in queue) {
            try {
                uploadPending(item)
                item.files.values.forEach { path -> File(path).delete() }
                Log.i(TAG, "offline upload completed session=${item.sessionId}")
                // C22: 断网期间会话曾发 LOCAL_QUEUED，错过正常关门事件；上传成功后补发
                // CLOSED(uploadStatus=UPLOADED) 门事件，经 OutboundMqttQueue 持久化补投。
                if (item.closed) {
                    runCatching { closedEventPublisher?.invoke(item.sessionId, "UPLOADED") }
                        .onFailure { Log.w(TAG, "supplement CLOSED publish failed: ${it.message}") }
                }
            } catch (e: Exception) {
                Log.w(TAG, "offline upload retry session=${item.sessionId} attempt=${item.attempts + 1}: ${e.message}")
                if (item.attempts < EdgeRuntimeConfig.offlineUploadMaxAttempts(context)) {
                    remaining.add(item.copy(attempts = item.attempts + 1))
                } else {
                    Log.e(TAG, "offline upload abandoned session=${item.sessionId}")
                    // H62a: 重试达到上限放弃时发布告警（发布失败仅日志，防循环）
                    runCatching {
                        alertPublisher?.invoke(
                            "EDGE_QUEUE_ABANDON",
                            "offline upload abandoned session=${item.sessionId} attempts=${item.attempts + 1}"
                        )
                    }.onFailure { Log.w(TAG, "abandon alert publish failed: ${it.message}") }
                }
            }
        }
        store.replaceAll(remaining)
    }

    private fun uploadPending(item: PendingUpload) {
        val uriPairs = item.files.map { (camera, path) ->
            val file = File(path)
            if (!file.exists()) throw IllegalStateException("missing file $path")
            camera to minioUploader.uploadVideoStrict(item.sessionId, file, camera, item.deviceId, item.userId)
        }
        val primaryUri = uriPairs.first { it.first == "TOP" }.second
        val clipsJson = if (item.fusionMode == "MULTI" && uriPairs.size >= 2) {
            VideoClipJson.build(uriPairs)
        } else {
            null
        }
        TradeVideoClient.attachVideo(item.sessionId, primaryUri, item.fusionMode, clipsJson)
    }

    companion object {
        private const val TAG = "OfflineUploadQueue"
        private const val PREFS = "offline_upload_queue"
    }
}
