package com.aicabinet.edge.ota

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * 安装编排与结果回收单测。
 *
 * 为什么值得测：这段代码**在真机上才会真正执行**，而本机没有 emulator/真机
 * （见 `EDGE-ANDROID-GRADLE.md` §8）。所以「不能被单测覆盖」的部分必须被压到最小 ——
 * 能被抽出来的**决策**（包校验、属主判定、失败原因、台账清理时机）全部钉在这里，
 * 留给真机验收的只剩「`PackageInstaller` 提交本身是否成功」。
 *
 * ⚠️ 明确没写的判据：「DeviceOwner 下真的静默装上了」。它需要真机 + 出厂预置，
 *    写不出来就是写不出来 —— 不写假的替代判据（比如断言 `createSession` 被调用），
 *    那只会给出「已覆盖」的错觉。
 */
@RunWith(RobolectricTestRunner::class)
class OtaInstallerTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        OtaProgressReporter.transportForTest = null
        OtaUpgradeLedger.clear(ctx)
    }

    // ---------- 安装包校验 ----------

    @Test
    fun `安装包不存在时直接失败且不判为已提交`() {
        val result = OtaInstaller.install(ctx, File(ctx.cacheDir, "nope.apk"), "0.6.1")
        assertEquals(OtaInstallMode.FAILED, result.mode)
        assertFalse("没有包就不能算提交成功", result.submitted)
        assertNotNull(result.reason)
    }

    @Test
    fun `空文件同样被拒`() {
        val empty = File(ctx.cacheDir, "empty.apk").apply { writeBytes(ByteArray(0)) }
        val result = OtaInstaller.install(ctx, empty, "0.6.1")
        assertEquals(OtaInstallMode.FAILED, result.mode)
        assertFalse(result.submitted)
    }

    // ---------- 属主判定与降级 ----------

    @Test
    fun `非 DeviceOwner 时不静默提交而是给出待运维原因`() {
        assertFalse("Robolectric 默认不是设备属主", OtaInstaller.isDeviceOwner(ctx))
        val apk = File(ctx.cacheDir, "u.apk").apply { writeBytes(ByteArray(32)) }

        val result = OtaInstaller.install(ctx, apk, "0.6.1")

        assertEquals(OtaInstallMode.NOT_DEVICE_OWNER, result.mode)
        assertFalse("非属主绝不能当成已提交 —— 否则会静默地永远装不上", result.submitted)
        assertTrue("原因里要能看出是 DeviceOwner 缺位", result.reason!!.contains("DeviceOwner"))
    }

    // ---------- 安装结果回收 ----------

    @Test
    fun `安装失败回调清掉台账并上报 FAILED 原因`() {
        val posted = mutableListOf<String>()
        OtaProgressReporter.transportForTest = OtaProgressTransport { _, _, json ->
            posted += json
            200
        }
        OtaUpgradeLedger.markPending(ctx, "0.6.1", 1L)

        OtaInstallResultReceiver().onReceive(
            ctx,
            Intent(OtaInstallResultReceiver.ACTION_INSTALL_RESULT)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                .putExtra(PackageInstaller.EXTRA_STATUS_MESSAGE, "INSTALL_FAILED_VERIFICATION_FAILURE"),
        )

        assertEquals(1, posted.size)
        val node = jacksonObjectMapper().readTree(posted[0])
        assertEquals("FAILED", node["status"].asText())
        assertEquals("INSTALL_FAILED_VERIFICATION_FAILURE", node["errorMessage"].asText())
        assertEquals("0.6.1", node["targetVersion"].asText())
        assertNull("已即时上报失败，台账要清掉以免每次启动重复报同一条", OtaUpgradeLedger.pendingTarget(ctx))
    }

    @Test
    fun `系统要求用户确认时报成失败而非静默丢弃`() {
        val posted = mutableListOf<String>()
        OtaProgressReporter.transportForTest = OtaProgressTransport { _, _, json ->
            posted += json
            200
        }
        OtaUpgradeLedger.markPending(ctx, "0.6.1", 1L)

        OtaInstallResultReceiver().onReceive(
            ctx,
            Intent(OtaInstallResultReceiver.ACTION_INSTALL_RESULT)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION),
        )

        assertEquals(1, posted.size)
        val node = jacksonObjectMapper().readTree(posted[0])
        assertEquals("FAILED", node["status"].asText())
        assertTrue(node["errorMessage"].asText().contains("用户确认"))
    }

    @Test
    fun `系统回报成功时不报 SUCCESS 也不清台账`() {
        var posts = 0
        OtaProgressReporter.transportForTest = OtaProgressTransport { _, _, _ ->
            posts++
            200
        }
        OtaUpgradeLedger.markPending(ctx, "0.6.1", 1L)

        OtaInstallResultReceiver().onReceive(
            ctx,
            Intent(OtaInstallResultReceiver.ACTION_INSTALL_RESULT)
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS),
        )

        // 「系统接受了会话」≠「新版本在跑」。此刻报 SUCCESS 就是假绿；
        // 真正生效与否留给下次启动的版本号比对（OtaChecker.settlePreviousAttempt）。
        assertEquals(0, posts)
        assertEquals("0.6.1", OtaUpgradeLedger.pendingTarget(ctx))
        assertTrue("验收窗口起点应被挪到此刻", OtaUpgradeLedger.pendingSubmittedAt(ctx) > 1L)
    }

    @Test
    fun `非本接收器的广播被忽略`() {
        var posts = 0
        OtaProgressReporter.transportForTest = OtaProgressTransport { _, _, _ ->
            posts++
            200
        }
        OtaUpgradeLedger.markPending(ctx, "0.6.1", 1L)

        OtaInstallResultReceiver().onReceive(ctx, Intent("android.intent.action.SOMETHING_ELSE"))

        assertEquals(0, posts)
        assertEquals("0.6.1", OtaUpgradeLedger.pendingTarget(ctx))
    }
}
