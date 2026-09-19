package com.aicabinet.edge.video

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `VideoClipJson.build` 单测。
 *
 * 为什么值得测：这段 JSON 是**双摄录像产物上报给 trade-service 的报文**，
 * 服务端按 `camera` 区分前后摄、按 `videoUri` 取流。字段名写错或漏字段不会在
 * 端侧报错 —— 只会让服务端拿到缺字段的 clips，事后取证时发现只有一路视频。
 *
 * 用 Jackson 解析断言而非字符串硬比：断言字段语义，不是断言序列化器的空格风格。
 *
 * ⚠️ 一条**刻意没写成判据**的性质：「一次调用内所有 clip 共用同一个 capturedAt」。
 *    它无法作为黑盒判据失败 —— 实测把实现从「map 之外取一次 now」改成「map 之内逐条取
 *    System.currentTimeMillis()」（A/B 用例 D4 首版），同一毫秒内两次取值相同，等值断言
 *    照样绿，属本项目固化的失效形态③（判据恒真）。要真正钉住它，得先把时钟做成可注入的
 *    seam（改生产签名）；而这点毫秒级差异对「服务端按时间对齐两路视频」没有实际影响，
 *    故本批不动生产代码，只保留下面那条**能失败**的判据（时间戳必须落在本次调用窗口内）。
 */
class VideoClipJsonTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `空 clip 列表产出空 JSON 数组`() {
        val node = mapper.readTree(VideoClipJson.build(emptyList()))
        assertTrue("应为数组", node.isArray)
        assertEquals(0, node.size())
    }

    @Test
    fun `单摄产出单个 clip 且字段齐全`() {
        val node = mapper.readTree(VideoClipJson.build(listOf("back" to "file:///s/1.mp4")))
        assertEquals(1, node.size())
        val clip = node[0]
        assertEquals("back", clip["camera"].asText())
        assertEquals("file:///s/1.mp4", clip["videoUri"].asText())
        assertTrue("capturedAt 必须是数值", clip["capturedAt"].isNumber)
    }

    @Test
    fun `双摄按入参顺序产出两路且各自 camera 不串`() {
        val node =
            mapper.readTree(
                VideoClipJson.build(
                    listOf("front" to "file:///s/f.mp4", "back" to "file:///s/b.mp4")
                )
            )
        assertEquals(2, node.size())
        assertEquals("front", node[0]["camera"].asText())
        assertEquals("file:///s/f.mp4", node[0]["videoUri"].asText())
        assertEquals("back", node[1]["camera"].asText())
        assertEquals("file:///s/b.mp4", node[1]["videoUri"].asText())
    }

    @Test
    fun `capturedAt 落在本次调用窗口内 —— 不是常量、不是 0、不是秒级`() {
        val before = System.currentTimeMillis()
        val node =
            mapper.readTree(
                VideoClipJson.build(
                    listOf("front" to "file:///s/f.mp4", "back" to "file:///s/b.mp4")
                )
            )
        val after = System.currentTimeMillis()

        // 逐条都判：把它写成常量（如 0）或秒级时间戳都会红。
        // ⚠️ 这里**故意不断言两条相等** —— 那是个恒真判据，见类注释。
        for (i in 0 until node.size()) {
            val ts = node[i]["capturedAt"].asLong()
            assertTrue(
                "clips[$i].capturedAt=$ts 应落在调用窗口 $before..$after",
                ts in before..after
            )
        }
    }
}
