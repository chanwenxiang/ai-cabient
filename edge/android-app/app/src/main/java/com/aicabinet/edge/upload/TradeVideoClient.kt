package com.aicabinet.edge.upload

import android.util.Log
import com.aicabinet.edge.AiCabinetApp
import com.aicabinet.edge.config.EdgeRuntimeConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class VideoUploadPresign(
    val objectKey: String,
    val uploadUrl: String,
    val videoUri: String
)

object TradeVideoClient {
    private const val TAG = "TradeVideoClient"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun requestVideoUploadPresign(
        sessionId: String,
        deviceId: String,
        userId: Long,
        camera: String,
        extension: String
    ): VideoUploadPresign? {
        val ctx = AiCabinetApp.instance
        val tradeUrl = EdgeRuntimeConfig.tradeServiceUrl(ctx).trimEnd('/')
        val body = JSONObject().apply {
            put("sessionId", sessionId)
            put("deviceId", deviceId)
            put("userId", userId)
            put("camera", camera)
            put("extension", extension)
        }
        val request = Request.Builder()
            .url("$tradeUrl/internal/v1/sessions/video-upload-url")
            .header("Content-Type", "application/json")
            .header("X-Internal-Api-Key", EdgeRuntimeConfig.internalApiKey(ctx))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "presign HTTP ${response.code}")
                    return null
                }
                val json = JSONObject(response.body?.string() ?: return null)
                val data = json.optJSONObject("data") ?: return null
                VideoUploadPresign(
                    objectKey = data.getString("objectKey"),
                    uploadUrl = data.getString("uploadUrl"),
                    videoUri = data.getString("videoUri")
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "presign request failed session=$sessionId", e)
            null
        }
    }

    fun attachVideo(
        sessionId: String,
        videoUri: String,
        cameraFusionMode: String = "SINGLE",
        videoClipsJson: String? = null
    ) {
        val ctx = AiCabinetApp.instance
        val tradeUrl = EdgeRuntimeConfig.tradeServiceUrl(ctx).trimEnd('/')
        val body = JSONObject().apply {
            put("sessionId", sessionId)
            put("deviceId", EdgeRuntimeConfig.deviceId(ctx))
            put("videoUri", videoUri)
            put("uploadStatus", "UPLOADED")
            put("cameraFusionMode", cameraFusionMode)
            if (videoClipsJson != null) put("videoClipsJson", videoClipsJson)
        }
        val request = Request.Builder()
            .url("$tradeUrl/internal/v1/sessions/video")
            .header("Content-Type", "application/json")
            .header("X-Internal-Api-Key", EdgeRuntimeConfig.internalApiKey(ctx))
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                throw IllegalStateException("attach video HTTP ${response.code} $err")
            }
            Log.i(TAG, "video attached session=$sessionId fusion=$cameraFusionMode")
        }
    }
}
