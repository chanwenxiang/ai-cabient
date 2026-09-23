package com.aicabinet.edge.ota

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/** 安装编排的三种去向。 */
enum class OtaInstallMode {
    /** 已把安装会话提交给系统（DeviceOwner 静默安装）。 */
    SILENT_INSTALLER,

    /** 设备未预置 DeviceOwner ⇒ 静默安装不可用，需运维介入。 */
    NOT_DEVICE_OWNER,

    /** 其它失败（包缺失/系统拒绝/异常）。 */
    FAILED,
}

data class OtaInstallResult(val mode: OtaInstallMode, val reason: String?) {
    val submitted: Boolean get() = mode == OtaInstallMode.SILENT_INSTALLER
}

/**
 * OTA 安装编排（O2）。
 *
 * 这是 [OtaChecker] 里那句「安装由运维/后续 PackageInstaller 流程接管」注释所指向的缺口。
 *
 * 为什么必须显式区分 [OtaInstallMode.NOT_DEVICE_OWNER]：非属主设备上 `PackageInstaller`
 * 会走 `STATUS_PENDING_USER_ACTION`（弹窗要人点），柜机无人值守 ⇒ 静默升级**必然**失败。
 * 旧行为是只落地安装包、什么都不说，于是运营台看到的现象和「服务端根本没下发更新」一样。
 * 现在把它变成一个可上报的明确原因。
 */
object OtaInstaller {
    private const val TAG = "OtaInstaller"
    private const val WRITE_NAME = "base.apk"

    fun isDeviceOwner(context: Context): Boolean = try {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        dpm?.isDeviceOwnerApp(context.packageName) == true
    } catch (e: Exception) {
        Log.w(TAG, "isDeviceOwnerApp unavailable: ${e.message}")
        false
    }

    /**
     * 提交静默安装。返回 [OtaInstallResult] 供调用方上报 —— 本方法**不自己上报**，
     * 上报时机与 targetVersion 由调用方掌握（避免两处各报一半、报文互相覆盖）。
     */
    fun install(context: Context, apk: File, targetVersion: String): OtaInstallResult {
        if (!apk.isFile || apk.length() <= 0L) {
            return OtaInstallResult(OtaInstallMode.FAILED, "安装包不存在或为空")
        }
        if (!isDeviceOwner(context)) {
            return OtaInstallResult(
                OtaInstallMode.NOT_DEVICE_OWNER,
                "设备未预置为 DeviceOwner，无法静默安装（需运维手动升级或补做设备预置）",
            )
        }
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                session.openWrite(WRITE_NAME, 0, apk.length()).use { out ->
                    apk.inputStream().use { input -> input.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(commitIntentSender(context, sessionId))
            }
            Log.i(TAG, "OTA silent install submitted session=$sessionId target=$targetVersion")
            OtaInstallResult(OtaInstallMode.SILENT_INSTALLER, null)
        } catch (e: Exception) {
            // 常见于缺 REQUEST_INSTALL_PACKAGES 或非属主：转成原因上抛，绝不静默。
            Log.e(TAG, "OTA silent install submit failed: ${e.message}", e)
            OtaInstallResult(OtaInstallMode.FAILED, "静默安装提交失败：${e.javaClass.simpleName}")
        }
    }

    private fun commitIntentSender(context: Context, sessionId: Int): IntentSender {
        val intent = Intent(context, OtaInstallResultReceiver::class.java)
            .setAction(OtaInstallResultReceiver.ACTION_INSTALL_RESULT)
            .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 要求显式声明可变性；安装结果回调需要系统往里写 extras。
            flags = flags or PendingIntent.FLAG_MUTABLE
        }
        return PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender
    }
}
