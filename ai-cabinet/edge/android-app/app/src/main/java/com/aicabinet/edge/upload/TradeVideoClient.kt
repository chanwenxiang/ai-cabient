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

object TradeVideoClient {
    private const val TAG = "TradeVideoClient"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
