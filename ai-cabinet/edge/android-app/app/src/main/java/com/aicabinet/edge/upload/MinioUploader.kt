package com.aicabinet.edge.upload

import android.util.Log
import com.aicabinet.edge.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * MinIO/S3 兼容上传。默认连接 docker-compose MinIO。
 */
class MinioUploader(
    private val endpoint: String = BuildConfig.MINIO_ENDPOINT,
    private val bucket: String = BuildConfig.MINIO_BUCKET,
    private val accessKey: String = BuildConfig.MINIO_ACCESS_KEY,
    private val secretKey: String = BuildConfig.MINIO_SECRET_KEY
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 上传失败时抛出异常，供断网续传队列使用。 */
    @Throws(IOException::class)
    fun uploadVideoStrict(sessionId: String, file: File, cameraLabel: String = ""): String {
        val tag = if (cameraLabel.isBlank()) {
            UUID.randomUUID().toString().take(8)
        } else {
            "${cameraLabel.lowercase()}-${UUID.randomUUID().toString().take(8)}"
        }
        val objectKey = "videos/${datePath()}/$sessionId-$tag.mp4"
        val url = "$endpoint/$bucket/$objectKey"
        val request = Request.Builder()
            .url(url)
            .put(file.asRequestBody("video/mp4".toMediaType()))
            .header("Authorization", "Basic ${basicAuth()}")
            .build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("minio upload HTTP ${resp.code}")
            }
        }
        val uri = "minio://$bucket/$objectKey"
        Log.i(TAG, "uploaded video uri=$uri")
        return uri
    }

    private fun basicAuth(): String {
        val cred = "$accessKey:$secretKey"
        return android.util.Base64.encodeToString(cred.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun datePath(): String =
        SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())

    companion object {
        private const val TAG = "MinioUploader"
    }
}
