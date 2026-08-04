package com.aicabinet.edge.ota

import android.util.Log
import com.aicabinet.edge.config.EdgeRuntimeConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OtaChecker {
    private const val TAG = "OtaChecker"
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    fun checkOnStartup(context: android.content.Context) {
        val tradeUrl = EdgeRuntimeConfig.tradeServiceUrl(context).trimEnd('/')
        val apiKey = EdgeRuntimeConfig.internalApiKey(context)
        val deviceId = EdgeRuntimeConfig.deviceId(context)
        val url = "$tradeUrl/internal/v1/devices/$deviceId/ota/check" +
                "?currentVersion=${com.aicabinet.edge.BuildConfig.VERSION_NAME}&channel=stable"
        try {
            val request = Request.Builder()
                .url(url)
                .header("X-Internal-Api-Key", apiKey)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "OTA check HTTP ${response.code}")
                    return
                }
                val body = response.body?.string() ?: return
                val data = JSONObject(body).optJSONObject("data") ?: return
                if (data.optBoolean("updateAvailable", false)) {
                    Log.i(TAG, "OTA available: ${data.optString("targetVersion")} " +
                            "mandatory=${data.optBoolean("mandatory")} url=${data.optString("downloadUrl")}")
                } else {
                    Log.i(TAG, "OTA up to date")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "OTA check skipped: ${e.message}")
        }
    }
}
