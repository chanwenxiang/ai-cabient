package com.aicabinet.edge.upload

import android.content.Context
import android.util.Log
import com.aicabinet.edge.config.EdgeRuntimeConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 购物录像上传：通过 trade-service 预签名 URL 写入 MinIO，柜机不持有对象存储凭证。
 * E-P2-6：连续失败触发断路器冷却，避免长时间打满离线队列。
 */
class MinioUploader(
    private val context: Context? = null
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val consecutiveFailures = AtomicInteger(0)
    private val circuitOpenUntilMs = AtomicLong(0L)

    /** 上传失败时抛出异常，供断网续传队列使用。 */
    @Throws(IOException::class)
    fun uploadVideoStrict(
        sessionId: String,
        file: File,
        cameraLabel: String = "",
        deviceId: String = "",
        userId: Long = 0L
    ): String {
        ensureCircuitClosed()
        return try {
            val ext = when {
                file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true) -> ".jpg"
                file.name.endsWith(".png", true) -> ".png"
                else -> ".mp4"
            }
            val camera = cameraLabel.ifBlank { "top" }
            val presign = TradeVideoClient.requestVideoUploadPresign(sessionId, deviceId, userId, camera, ext)
                ?: throw IOException("failed to obtain presigned upload URL for session=$sessionId")
            uploadToPresignedUrl(presign.uploadUrl, file, contentTypeFor(ext))
            consecutiveFailures.set(0)
            Log.i(TAG, "uploaded video via presign uri=${presign.videoUri}")
            presign.videoUri
        } catch (e: IOException) {
            onUploadFailure(e)
            throw e
        }
    }

    private fun ensureCircuitClosed() {
        val openUntil = circuitOpenUntilMs.get()
        if (openUntil > 0L && System.currentTimeMillis() < openUntil) {
            throw IOException("minio circuit open until=$openUntil")
        }
        if (openUntil > 0L) {
            circuitOpenUntilMs.compareAndSet(openUntil, 0L)
            consecutiveFailures.set(0)
            Log.i(TAG, "minio circuit half-open, retry allowed")
        }
    }

    private fun onUploadFailure(error: IOException) {
        val ctx = context ?: return
        val threshold = EdgeRuntimeConfig.minioCircuitFailureThreshold(ctx)
        val failures = consecutiveFailures.incrementAndGet()
        if (failures < threshold) {
            Log.w(TAG, "minio upload failure $failures/$threshold: ${error.message}")
            return
        }
        val openMs = EdgeRuntimeConfig.minioCircuitOpenMs(ctx)
        val until = System.currentTimeMillis() + openMs
        circuitOpenUntilMs.set(until)
        consecutiveFailures.set(0)
        Log.e(TAG, "minio circuit open for ${openMs}ms after $threshold failures")
    }

    @Throws(IOException::class)
    private fun uploadToPresignedUrl(url: String, file: File, contentType: String) {
        val request = Request.Builder()
            .url(url)
            .put(file.asRequestBody(contentType.toMediaType()))
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("presign upload HTTP ${resp.code}")
            }
        }
    }

    private fun contentTypeFor(ext: String): String = when (ext.lowercase()) {
        ".jpg", ".jpeg" -> "image/jpeg"
        ".png" -> "image/png"
        else -> "video/mp4"
    }

    companion object {
        private const val TAG = "MinioUploader"
    }
}
