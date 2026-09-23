package com.aicabinet.edge.ota

import android.content.Context
import android.util.Log
import com.aicabinet.edge.BuildConfig
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
 *
 * O2 收口：本条链路此前只有「终态一个信号」—— 下载、安装过程中的进度与失败原因全部无处落地，
 * 于是「卡在下载 60%」「安装失败」的柜机在运营台看起来和「压根没开始升级」完全一样。
 * 现在每个状态点都会上报到 `POST /internal/v1/devices/{id}/ota/progress`（服务端早已就绪、
 * 此前零调用），并把安装交给 [OtaInstaller] 静默执行。
 *
 * 状态机：DOWNLOADING(0→100) → INSTALLING(100) → [重启后] SUCCESS；任一步失败 → FAILED + 原因。
 */
object OtaChecker {
    private const val TAG = "OtaChecker"

    /** 下载进度上报粒度。同步上报（不另起线程）以保持顺序可断言；同内网开销可忽略。 */
    private const val DOWNLOAD_PROGRESS_STEP = 20

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    fun checkOnStartup(context: Context) {
        settlePreviousAttempt(context)
        val tradeUrl = EdgeRuntimeConfig.tradeServiceUrl(context).trimEnd('/')
        val apiKey = EdgeRuntimeConfig.internalApiKey(context)
        val deviceId = EdgeRuntimeConfig.ensureDeviceId(context)
        val url = "$tradeUrl/internal/v1/devices/$deviceId/ota/check" +
                "?currentVersion=${BuildConfig.VERSION_NAME}&channel=stable"
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
                    OtaProgressReporter.report(context, OtaUpgradeStatus.FAILED, targetVersion, null, "服务端未下发下载地址")
                    return
                }
                if (checksum.isBlank() || checksum.length != 64) {
                    Log.e(TAG, "OTA rejected: checksumSha256 required (64 hex); refusing insecure update")
                    OtaProgressReporter.report(context, OtaUpgradeStatus.FAILED, targetVersion, null, "服务端未下发有效 SHA-256，拒绝不安全升级")
                    return
                }
                runInstall(context, downloadUrl, checksum, targetVersion)
            }
        } catch (e: Exception) {
            Log.w(TAG, "OTA check skipped: ${e.message}", e)
        }
    }

    /** 下载 → 校验 → 提交静默安装，并在每个状态点上报。 */
    private fun runInstall(context: Context, downloadUrl: String, expectedSha256: String, targetVersion: String) {
        OtaProgressReporter.report(context, OtaUpgradeStatus.DOWNLOADING, targetVersion, 0)
        val apk = downloadAndVerify(context, downloadUrl, expectedSha256, targetVersion)
        if (apk == null) {
            OtaProgressReporter.report(context, OtaUpgradeStatus.FAILED, targetVersion, null, "下载或 SHA-256 校验失败，已丢弃安装包")
            return
        }
        OtaProgressReporter.report(context, OtaUpgradeStatus.INSTALLING, targetVersion, 100)
        val result = OtaInstaller.install(context, apk, targetVersion)
        if (result.submitted) {
            // 记账，等下次启动用版本号验收「是否真的生效」（提交成功 ≠ 在跑）。
            OtaUpgradeLedger.markPending(context, targetVersion, System.currentTimeMillis())
        } else {
            OtaProgressReporter.report(context, OtaUpgradeStatus.FAILED, targetVersion, null, result.reason)
        }
    }

    /**
     * 结算上一次安装的结局。
     *
     * 成功的唯一判据是**物理证据**：本次启动读到的 `VERSION_NAME` 等于目标版本。
     * 只靠 `PackageInstaller` 回调报成功是假绿 —— 它只能证明「系统接受了会话」。
     */
    private fun settlePreviousAttempt(context: Context) {
        val pending = OtaUpgradeLedger.pendingTarget(context) ?: return
        val current = BuildConfig.VERSION_NAME
        when (OtaUpgradeLedger.settle(
            pendingTarget = pending,
            currentVersion = current,
            submittedAtMs = OtaUpgradeLedger.pendingSubmittedAt(context),
            nowMs = System.currentTimeMillis(),
        )) {
            OtaSettlement.SUCCEEDED -> {
                Log.i(TAG, "OTA settled: $pending is now running")
                OtaProgressReporter.report(context, OtaUpgradeStatus.SUCCESS, pending, 100)
                OtaUpgradeLedger.clear(context)
            }

            OtaSettlement.DID_NOT_TAKE_EFFECT -> {
                Log.w(TAG, "OTA settled: target $pending did not take effect (running $current)")
                OtaProgressReporter.report(
                    context,
                    OtaUpgradeStatus.FAILED,
                    pending,
                    null,
                    "安装未生效：目标 $pending，当前运行 $current",
                )
                // 清账：否则每次启动都会重复报同一条失败。
                OtaUpgradeLedger.clear(context)
            }

            OtaSettlement.IN_PROGRESS -> Log.i(TAG, "OTA pending $pending still within grace window")

            OtaSettlement.NONE -> Unit
        }
    }

    /**
     * 下载进度的上报节流判据：距上次上报至少前进 [DOWNLOAD_PROGRESS_STEP] 个百分点。
     *
     * 抽成独立函数是为了让它**可被单测直接钉住** —— [downloadAndVerify] 本身要真下载，
     * 在 JVM 里无法覆盖；这条判据是那段循环里唯一的纯逻辑，漏了它整条链路就没有可失败的判据。
     *
     * @param lastPercent 上一次已上报的百分比（下载开始前已报过 0，故调用方以 0 起步）
     */
    internal fun shouldReportProgress(
        percent: Int,
        lastPercent: Int,
        step: Int = DOWNLOAD_PROGRESS_STEP,
    ): Boolean = percent - lastPercent >= step

    /** @return 校验通过并落地的安装包；失败返回 null（并保证不留残包）。 */
    private fun downloadAndVerify(
        context: Context,
        downloadUrl: String,
        expectedSha256: String,
        targetVersion: String
    ): File? {
        val dir = File(context.cacheDir, "ota").apply { mkdirs() }
        val apk = File(dir, "update-$targetVersion.apk")
        try {
            val request = Request.Builder().url(downloadUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "OTA download HTTP ${response.code}")
                    return null
                }
                val body = response.body ?: run {
                    Log.e(TAG, "OTA download empty body")
                    return null
                }
                val total = body.contentLength()
                val digest = MessageDigest.getInstance("SHA-256")
                var written = 0L
                // 0% 已在下载开始前显式上报过，故起点是 0 而非 -1（否则第一个 20% 会被吞掉）。
                var lastPercent = 0
                FileOutputStream(apk).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            digest.update(buf, 0, n)
                            out.write(buf, 0, n)
                            written += n
                            if (total > 0) {
                                val percent = ((written * 100) / total).toInt().coerceAtMost(100)
                                if (shouldReportProgress(percent, lastPercent)) {
                                    lastPercent = percent
                                    // 运营台就靠这一行区分「卡在下载」与「还没开始」。
                                    OtaProgressReporter.report(
                                        context, OtaUpgradeStatus.DOWNLOADING, targetVersion, percent,
                                    )
                                }
                            }
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { b -> "%02x".format(b) }
                if (actual != expectedSha256) {
                    Log.e(TAG, "OTA checksum mismatch expected=$expectedSha256 actual=$actual — deleting package")
                    apk.delete()
                    return null
                }
                Log.i(TAG, "OTA package verified sha256=$actual path=${apk.absolutePath} — handing to installer")
                return apk
            }
        } catch (e: Exception) {
            Log.e(TAG, "OTA download/verify failed: ${e.message}", e)
            if (apk.exists()) {
                apk.delete()
            }
        }
        return null
    }
}
