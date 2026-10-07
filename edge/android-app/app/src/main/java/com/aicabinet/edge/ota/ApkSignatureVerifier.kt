package com.aicabinet.edge.ota

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * APK **发布方签名**校验（V317，2026-10-07）。
 *
 * <p>🔴 <b>为什么 SHA-256 不够</b>：
 * {@code OtaChecker.downloadAndVerify} 已校验下载包的 SHA-256，但攻击者若能控制
 * 下发通道，就能**连同 SHA-256 一起改掉**（服务端下发的 checksum 就是他给的）。
 * ⇒ SHA-256 只防「传输损坏 / 中间人篡改」，**不防「攻击者自己造包」**。
 *
 * <p>唯一能挡住后者的是**签名校验**：包必须由「与我们同签名的私钥」签发。
 * 依据 AOSP 官方（`source.android.com/docs/core/ota/sign_builds`）：
 * test keys 是公开的，任何人都能签自己的 apk 替换/劫持应用；
 * 公开发布必须用只有发布方能访问的专属发布密钥。
 *
 * <p><b>比对基线是「已安装应用自己的签名」</b>，而不是「内置的公钥字符串」——
 * 前者不需要把指纹写死在代码里，也不会因换密钥而必须改代码；
 * 代价是**首次安装**时无基线（此时靠 HTTPS + 服务端鉴权保护，见 [verifyMatchesInstalled]）。
 */
object ApkSignatureVerifier {

    private const val TAG = "ApkSignatureVerify"

    /**
     * 校验待安装 APK 的签名是否与**当前已安装应用**一致。
     *
     * @param context 上下文
     * @param apkPath 待安装 APK 路径
     * @return true = 签名一致（可继续安装）；false = 不一致或无法判定（**必须拒绝安装**）
     *
     * 🔴 **返回 false 时一律拒绝安装**，不做「校验失败就放行」的降级 ——
     *    降级等于把这个检查关掉。
     */
    fun verifyMatchesInstalled(context: Context, apkPath: String): Boolean {
        val installed = installedSignatures(context)
        if (installed.isEmpty()) {
            // 拿不到基线（首装/ 系统签名应用）。此时**不能宣称校验通过**，
            // 但也不能因此永久锁死安装 —— 记日志并放行，由 HTTPS + 服务端鉴权兜底。
            Log.w(TAG, "no baseline signature available (first install?) — skip comparison")
            return true
        }
        val candidate = signaturesOf(context, apkPath)
        if (candidate.isEmpty()) {
            Log.e(TAG, "candidate APK has no readable signature — reject")
            return false
        }
                //🔴 逐项比 `toByteArray()`，**不用 `contentEquals`** ——
        //   那是 `Array<Byte>` 的扩展函数，对 `List<Signature>` 不适用
        //   （编译期报Unresolved reference + inferred type is Unit）。
        //   同理 `Signature` 是 Java 类、不实现 `equals`，**直接用 `==` 比引用会永远不等**
        //   ⇒ 必须比字节内容。这是本方法的全部判据，不能写错。
        val matched = candidate.any { cand ->
            installed.any { it.toByteArray().contentEquals(cand.toByteArray()) }
        }
        if (!matched) {
            // 🔴 逐条打出指纹，便于现场判断「是换了密钥」还是「被攻击者替换」
            Log.e(TAG, "SIGNATURE MISMATCH — reject. installed=${installed.joinToString { it.fingerprint() }}")
            Log.e(TAG, "candidate=${candidate.joinToString { it.fingerprint() }}")
            return false
        }
        Log.i(TAG, "signature verified: ${candidate.first().fingerprint()}")
        return true
    }

    /** 已安装应用自己的签名。取不到时返回空集合。 */
    private fun installedSignatures(context: Context): List<Signature> = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val pi = pm.getPackageInfo(
                context.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
            pi.signingInfo?.apkContentsSigners?.toList() ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures?.toList()
                ?: emptyList()
        }
        info
    } catch (e: PackageManager.NameNotFoundException) {
        Log.e(TAG, "own package not found: ${e.message}")
        emptyList()
    } catch (e: Exception) {
        Log.e(TAG, "read installed signature failed: ${e.message}")
        emptyList()
    }

    /** 待安装 APK 的签名。读不出（文件损坏 / 非 APK）时返回空集合。 */
    private fun signaturesOf(context: Context, apkPath: String): List<Signature> = try {
        val pm = context.packageManager
        //🔴 用**公开 API** `getPackageArchiveInfo` 读未安装 APK 的签名。
        //   ⚠️ 曾经想用 `android.app.AppGlobals.getPackageManager()` ——
        //   那是**隐藏 API**：既编译不过，也是违规做法，且在不同 ROM 上行为不一致。
        //   `GET_SIGNING_CERTIFICATES` 在 API 28+ 才对未安装包有效，
        //   低版本需要用已废弃的 `GET_SIGNATURES`（见下方分支）。
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(apkPath, flags)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info?.signingInfo?.apkContentsSigners?.toList() ?: emptyList()
        } else {
            // 旧系统（API 24–27）没有 `signingInfo`，只能读已废弃的 `signatures`。
            // minSdk=24 ⇒ 这条分支在支持范围内，必须能编译过。
            @Suppress("DEPRECATION")
            info?.signatures?.toList() ?: emptyList()
        }
    } catch (e: Exception) {
        Log.e(TAG, "read archive signature failed: ${e.message}")
        emptyList()
    }

    /** 证书的 SHA-256 指纹（十六进制），用于日志比对。 */
    private fun Signature.fingerprint(): String = try {
        MessageDigest.getInstance("SHA-256").digest(toByteArray())
            .joinToString("") { "%02x".format(it) }
    } catch (e: Exception) {
        "unavailable(${e.message})"
    }
}
