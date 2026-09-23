package com.aicabinet.edge.ota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `OtaChecker` 里那段下载循环的**节流判据**单测。
 *
 * 为什么值得单独测：[OtaChecker.downloadAndVerify] 要真下载，JVM 单测覆盖不了它；
 * 而该循环里唯一的纯逻辑就是「距离上次上报够不够一个步长」。漏掉它，本次 O2 改动的
 * 主链路（下载进度上报）就**一条可失败的判据都没有** —— 只能靠现场观察。
 *
 * 节流本身是有代价的取舍：上报是同步 HTTP，太密会拖慢下载；太疏则运营台看不到
 * 「卡在 60%」这个关键现象。判据钉住的是「每前进一个步长恰好报一次」。
 */
class OtaCheckerTest {

    @Test
    fun `前进满一个步长才上报`() {
        assertTrue(OtaChecker.shouldReportProgress(percent = 20, lastPercent = 0))
        assertTrue(OtaChecker.shouldReportProgress(percent = 100, lastPercent = 80))
    }

    @Test
    fun `不足一个步长不重复上报`() {
        assertFalse(OtaChecker.shouldReportProgress(percent = 19, lastPercent = 0))
        assertFalse(OtaChecker.shouldReportProgress(percent = 0, lastPercent = 0))
    }

    @Test
    fun `进度回退不触发上报`() {
        // 用 `percent >= lastPercent + step` 而不是差值，会出现 19 >= -1+20 这种"起点 -1 时
        // 第一个 19% 就误报"的边界；这里同时把回退情形钉住。
        assertFalse(OtaChecker.shouldReportProgress(percent = 10, lastPercent = 20))
    }

    @Test
    fun `步长可调且默认 20`() {
        assertTrue(OtaChecker.shouldReportProgress(percent = 5, lastPercent = 0, step = 5))
        assertFalse(OtaChecker.shouldReportProgress(percent = 5, lastPercent = 0, step = 10))
    }
}
