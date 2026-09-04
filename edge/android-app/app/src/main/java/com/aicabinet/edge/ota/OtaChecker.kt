package com.aicabinet.edge.ota

import android.content.Context
import android.util.Log
import com.aicabinet.edge.config.EdgeRuntimeConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 启动时检查 OTA；若有更新则下载 APK 并强制校验 SHA-256，
 * 校验失败拒绝安装（不写安装包）。
 */
object OtaChecker {
    private const val TAG = "OtaChecker"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun checkOnStartup(context: Context) {
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
                if (!data.optBoolean("updateAvailable", false)) {
                    Log.i(TAG, "OTA up to date")
                    return
                }
                val targetVersion = data.optString("targetVersion")
                val downloadUrl = data.optString("downloadUrl")
                val checksum = data.optString("checksumSha256").trim().lowercase()
                val mandatory = data.optBoolean("mandatory")
                Log.i(TAG, "OTA available: $targetVersion mandatory=$mandatory url=$downloadUrl")
                if (downloadUrl.isBlank()) {
                    Log.e(TAG, "OTA rejected: empty downloadUrl")
                    return
                }
                if (checksum.isBlank() || checksum.length != 64) {
                    Log.e(TAG, "OTA rejected: checksumSha256 required (64 hex); refusing insecure update")
                    return
                }
                downloadAndVerify(context, downloadUrl, checksum, targetVersion)
            }
        } catch (e: Exception) {
            Log.w(TAG, "OTA check skipped: ${e.message}", e)
        }
    }

    private fun downloadAndVerify(
        context: Context,
        downloadUrl: String,
        expectedSha256: String,
        targetVersion: String
    ) {
        val dir = File(context.cacheDir, "ota").apply { mkdirs() }
        val apk = File(dir, "update-$targetVersion.apk")
        try {
            val request = Request.Builder().url(downloadUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "OTA download HTTP ${response.code}")
                    return
                }
                val body = response.body ?: run {
                    Log.e(TAG, "OTA download empty body")
                    return
                }
                val digest = MessageDigest.getInstance("SHA-256")
                FileOutputStream(apk).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { b -> "%02x".format(b) }
                if (actual != expectedSha256) {
                    Log.e(TAG, "OTA checksum mismatch expected=$expectedSha256 actual=$actual — deleting package")
                    apk.delete()
                    return
                }
                Log.i(TAG, "OTA package verified sha256=$actual path=${apk.absolutePath} — ready for install")
                // 安装由运维/后续 PackageInstaller 流程接管；此处只保证校验通过的包落地
            }
        } catch (e: Exception) {
            Log.e(TAG, "OTA download/verify failed: ${e.message}", e)
            if (apk.exists()) {
                apk.delete()
            }
        }
    }
}
