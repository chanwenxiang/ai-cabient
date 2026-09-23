package com.aicabinet.edge.ota

import android.content.Context

/**
 * 上次安装的验收结论。
 *
 * 存在的理由：`PackageInstaller` 的「提交成功」**不等于**「新版本真的在跑」——
 * 静默安装可能被系统拒绝、被策略拦截、或装完但没重启。只上报提交成功就是**假绿**。
 * 因此成功的唯一判据是**物理证据**：下次启动时 `BuildConfig.VERSION_NAME` 确实等于目标版本。
 */
enum class OtaSettlement {
    /** 没有待验收的安装。 */
    NONE,

    /** 已提交安装但仍在宽限期内 —— 可能正在安装/重启，**不判失败**（避免假红）。 */
    IN_PROGRESS,

    /** 目标版本已在运行 ⇒ 升级真生效。 */
    SUCCEEDED,

    /** 过了宽限期版本仍未变 ⇒ 安装未生效，必须上报失败让运维介入。 */
    DID_NOT_TAKE_EFFECT,
}

/**
 * OTA 安装台账：记录「已提交安装、等待下次启动验收」的目标版本。
 *
 * 为什么用「下次启动比对版本号」而不是「等 `PackageInstaller` 回调」：
 * 安装过程会杀掉本进程，回调很可能来不及上报；而重启后读到的版本号是无法伪造的物理证据。
 */
object OtaUpgradeLedger {
    private const val PREFS = "edge_ota"
    private const val KEY_TARGET = "pending_target_version"
    private const val KEY_AT = "pending_submitted_at"

    /** 安装宽限期：提交后的这段时间内不判「未生效」（安装 + 重启通常远小于此）。 */
    const val INSTALL_GRACE_MS: Long = 15 * 60 * 1000L

    fun pendingTarget(context: Context): String? =
        prefs(context).getString(KEY_TARGET, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun pendingSubmittedAt(context: Context): Long = prefs(context).getLong(KEY_AT, 0L)

    /** 提交安装会话时调用。 */
    fun markPending(context: Context, targetVersion: String, nowMs: Long) {
        prefs(context).edit()
            .putString(KEY_TARGET, targetVersion.trim())
            .putLong(KEY_AT, nowMs)
            .apply()
    }

    /**
     * 系统回报安装成功时调用：把验收窗口的起点挪到这一刻。
     *
     * 不直接记「成功」—— 真正生效与否仍以重启后的版本号为准（见 [OtaSettlement]）。
     */
    fun touch(context: Context, nowMs: Long) {
        if (pendingTarget(context) == null) return
        prefs(context).edit().putLong(KEY_AT, nowMs).apply()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_TARGET).remove(KEY_AT).apply()
    }

    /**
     * 结算上次安装的结局。**纯函数、无 IO** ⇒ 可被单测穷举边界。
     *
     * 版本号**严格相等**才算成功：如果现场被手动装成了更高的版本，会判成
     * [OtaSettlement.DID_NOT_TAKE_EFFECT]（保守偏向「上报让人看见」而非「静默认为成功」）——
     * 这种假失败会带来一次多余的排查，而假成功会让升级静默地永远不生效。
     */
    fun settle(
        pendingTarget: String?,
        currentVersion: String,
        submittedAtMs: Long,
        nowMs: Long,
        graceMs: Long = INSTALL_GRACE_MS,
    ): OtaSettlement {
        val target = pendingTarget?.trim()?.takeIf { it.isNotEmpty() } ?: return OtaSettlement.NONE
        if (target == currentVersion.trim()) return OtaSettlement.SUCCEEDED
        return if (nowMs - submittedAtMs < graceMs) {
            OtaSettlement.IN_PROGRESS
        } else {
            OtaSettlement.DID_NOT_TAKE_EFFECT
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
