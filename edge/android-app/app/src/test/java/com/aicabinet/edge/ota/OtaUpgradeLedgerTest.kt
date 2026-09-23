package com.aicabinet.edge.ota

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 安装台账单测。
 *
 * 为什么值得测：[OtaUpgradeLedger.settle] 是「升级到底有没有生效」的**唯一判据**。
 * 它判错两个方向的代价不对称：
 * - 判成假成功 ⇒ 升级永远静默不生效，没人会发现（本项要消除的正是这个盲区）；
 * - 判成假失败 ⇒ 多一次排查。
 * 所以它必须**偏保守**，而「宽限期内不判失败」这条边界正是防假红的闸门。
 *
 * `settle` 是纯函数，可以穷举时间/版本组合；prefs 读写用 Robolectric 的真 SharedPreferences 验证。
 */
@RunWith(RobolectricTestRunner::class)
class OtaUpgradeLedgerTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        OtaUpgradeLedger.clear(ctx)
    }

    @Test
    fun `无待验收台账时结算为 NONE`() {
        assertEquals(OtaSettlement.NONE, OtaUpgradeLedger.settle(null, "0.6.0", 0L, Long.MAX_VALUE))
        assertEquals(OtaSettlement.NONE, OtaUpgradeLedger.settle("   ", "0.6.0", 0L, Long.MAX_VALUE))
    }

    @Test
    fun `当前版本等于目标即成功并忽略首尾空格`() {
        assertEquals(OtaSettlement.SUCCEEDED, OtaUpgradeLedger.settle(" 0.6.1 ", "0.6.1", 0L, 1L))
    }

    @Test
    fun `宽限期内版本未变不判失败`() {
        // 提交安装到重启完成之间会读到旧版本号 —— 此时判失败就是假红。
        val submittedAt = 1_000_000L
        assertEquals(
            OtaSettlement.IN_PROGRESS,
            OtaUpgradeLedger.settle("0.6.1", "0.6.0", submittedAt, submittedAt + 60_000L),
        )
    }

    @Test
    fun `超过宽限期版本仍未变则判未生效`() {
        val submittedAt = 1_000_000L
        assertEquals(
            OtaSettlement.DID_NOT_TAKE_EFFECT,
            OtaUpgradeLedger.settle(
                "0.6.1", "0.6.0", submittedAt, submittedAt + OtaUpgradeLedger.INSTALL_GRACE_MS + 1L,
            ),
        )
    }

    @Test
    fun `台账写入后可读出且 clear 后消失`() {
        OtaUpgradeLedger.markPending(ctx, "0.6.1", 123L)
        assertEquals("0.6.1", OtaUpgradeLedger.pendingTarget(ctx))
        assertEquals(123L, OtaUpgradeLedger.pendingSubmittedAt(ctx))

        OtaUpgradeLedger.clear(ctx)
        assertNull(OtaUpgradeLedger.pendingTarget(ctx))
        assertEquals(0L, OtaUpgradeLedger.pendingSubmittedAt(ctx))
    }

    @Test
    fun `touch 只挪动验收窗口起点不改目标`() {
        OtaUpgradeLedger.markPending(ctx, "0.6.1", 111L)
        OtaUpgradeLedger.touch(ctx, 999L)
        assertEquals("0.6.1", OtaUpgradeLedger.pendingTarget(ctx))
        assertEquals(999L, OtaUpgradeLedger.pendingSubmittedAt(ctx))
    }

    @Test
    fun `无台账时 touch 不会凭空造出台账`() {
        OtaUpgradeLedger.clear(ctx)
        OtaUpgradeLedger.touch(ctx, 999L)
        assertNull(OtaUpgradeLedger.pendingTarget(ctx))
    }
}
