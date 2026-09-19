package com.aicabinet.edge.video

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * `RecordingResult` 的派生属性单测。
 *
 * 为什么值得测：`fusionMode` 会被上报给 trade-service，服务端据此决定是否按
 * 双路（MULTI）做融合取证。边界正是 `clips.size >= 2` —— 0 与 1 都算 SINGLE。
 * 判错（比如写成 `> 2`）会让双摄柜子以单摄模式上报，服务端永远等不到第二路。
 * `primaryFile` 在 clips 为空时须为 null（调用方据此判断"这次没录到东西"），
 * 若实现写 `clips.first().file` 就会在空列表上崩。
 */
class RecordingResultTest {

    @Test
    fun `零 clip 时降级为 SINGLE 且无主文件`() {
        val result = RecordingResult("S-1", emptyList())
        assertEquals("SINGLE", result.fusionMode)
        assertNull(result.primaryFile)
    }

    @Test
    fun `单 clip 为 SINGLE 且主文件即该文件`() {
        val only = VideoClipFile("back", File("b.mp4"))
        val result = RecordingResult("S-2", listOf(only))
        assertEquals("SINGLE", result.fusionMode)
        assertSame(only.file, result.primaryFile)
    }

    @Test
    fun `两路及以上为 MULTI 且主文件取第一路`() {
        val first = VideoClipFile("front", File("f.mp4"))
        val second = VideoClipFile("back", File("b.mp4"))
        val result = RecordingResult("S-3", listOf(first, second))
        assertEquals("MULTI", result.fusionMode)
        assertSame(first.file, result.primaryFile)
    }
}
