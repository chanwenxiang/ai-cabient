package com.aicabinet.edge.upload

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 购物录像上传：通过 trade-service 预签名 URL 写入 MinIO，柜机不持有对象存储凭证。
 */
class MinioUploader {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 上传失败时抛出异常，供断网续传队列使用。 */
    @Throws(IOException::class)
    fun uploadVideoStrict(
        sessionId: String,
        file: File,
        cameraLabel: String = "",
        deviceId: String = "",
        userId: Long = 0L
    ): String {
        val ext = when {
            file.name.endsWith(".jpg", true) || file.name.endsWith(".jpeg", true) -> ".jpg"
            file.name.endsWith(".png", true) -> ".png"
            else -> ".mp4"
        }
        val camera = cameraLabel.ifBlank { "top" }
        val presign = TradeVideoClient.requestVideoUploadPresign(sessionId, deviceId, userId, camera, ext)
            ?: throw IOException("failed to obtain presigned upload URL for session=$sessionId")
        uploadToPresignedUrl(presign.uploadUrl, file, contentTypeFor(ext))
        Log.i(TAG, "uploaded video via presign uri=${presign.videoUri}")
        return presign.videoUri
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
