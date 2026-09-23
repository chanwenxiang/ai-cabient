package com.aicabinet.edge.ota

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * 接收 `PackageInstaller` 的安装结果回调。
 *
 * 只在**失败**时即时上报（让运维尽快看到），成功则留给 [OtaUpgradeLedger] 在下次启动时
 * 用版本号验收 —— 因为：
 * 1. 安装会杀掉本进程，成功回调常常来不及发出去；
 * 2. 「提交成功」不等于「新版本在跑」，在此直接报 SUCCESS 就是假绿。
 */
class OtaInstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return

        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val target = OtaUpgradeLedger.pendingTarget(context)

        when (status) {
            PackageInstaller.STATUS_SUCCESS -> {
                // 只挪动验收窗口起点：真正生效与否由重启后的版本号说了算。
                OtaUpgradeLedger.touch(context, System.currentTimeMillis())
                Log.i(TAG, "OTA install reported success by system; awaiting version check on next start")
            }

            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // 柜机无人值守 ⇒ 弹窗等于失败，必须让人看见原因。
                reportFailure(context, target, "系统要求用户确认安装（应用未成为 DeviceOwner）")
            }

            else -> {
                reportFailure(context, target, message ?: "installer_status_$status")
            }
        }
    }

    private fun reportFailure(context: Context, target: String?, reason: String) {
        Log.w(TAG, "OTA install failed: $reason")
        OtaProgressReporter.report(
            context = context,
            status = OtaUpgradeStatus.FAILED,
            targetVersion = target,
            errorMessage = reason,
        )
        // 已即时上报过失败，清掉台账 —— 免得下次启动再报一遍同样的失败。
        OtaUpgradeLedger.clear(context)
    }

    companion object {
        private const val TAG = "OtaInstallResult"
        const val ACTION_INSTALL_RESULT = "com.aicabinet.edge.ota.INSTALL_RESULT"
    }
}
