package com.aicabinet.edge.ota

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * 上报**报文契约**单测。
 *
 * 为什么值得测：这份报文是设备侧与 `POST /internal/v1/devices/{id}/ota/progress` 的唯一接口面。
 * 字段名写错、状态取值拼错、或把不该带的原因带上，**端侧都不会报错** ——
 * 只会让服务端 400（未知状态）或让库里留下一行自相矛盾的进度，
 * 而现象是「运营台看不到进度」，与「设备压根没上报」无法区分。
 *
 * 用 Jackson 解析后断言字段语义，而不是硬比字符串（避免把序列化器的空格风格写进判据）。
 */
class OtaProgressReporterTest {

    private val mapper = jacksonObjectMapper()

    @Test
    fun `FAILED 状态带出错误原因`() {
        val node = mapper.readTree(
            OtaProgressReporter.buildPayload(OtaUpgradeStatus.FAILED, "0.6.1", 0, "下载或 SHA-256 校验失败"),
        )
        assertEquals("FAILED", node["status"].asText())
        assertEquals("下载或 SHA-256 校验失败", node["errorMessage"].asText())
        assertEquals("0.6.1", node["targetVersion"].asText())
    }

    @Test
    fun `非 FAILED 状态即使传了原因也会被丢弃`() {
        // 与服务端 OtaService.reportProgress 的收敛一致：非 FAILED 一律把原因清空。
        // 两侧各写对 —— 而不是「两侧一起写错所以全绿」。
        val node = mapper.readTree(
            OtaProgressReporter.buildPayload(OtaUpgradeStatus.SUCCESS, "0.6.1", 100, "不该出现的原因"),
        )
        assertFalse("SUCCESS 报文不应含 errorMessage", node.has("errorMessage"))
    }

    @Test
    fun `进度收敛到 0-100 且报文里也是收敛后的值`() {
        assertEquals(0, OtaProgressReporter.clampPercent(null))
        assertEquals(0, OtaProgressReporter.clampPercent(-5))
        assertEquals(100, OtaProgressReporter.clampPercent(150))
        assertEquals(60, OtaProgressReporter.clampPercent(60))

        // 不能只测辅助函数：必须证明 buildPayload 真的用了它（否则断言与实现脱节）。
        val node = mapper.readTree(
            OtaProgressReporter.buildPayload(OtaUpgradeStatus.DOWNLOADING, "0.6.1", 150),
        )
        assertEquals(100, node["progressPercent"].asInt())
    }

    @Test
    fun `空目标版本不发送该字段`() {
        val node = mapper.readTree(
            OtaProgressReporter.buildPayload(OtaUpgradeStatus.IDLE, "   ", null),
        )
        assertFalse(node.has("targetVersion"))
        assertEquals("IDLE", node["status"].asText())
    }

    @Test
    fun `上报地址归一去尾斜杠并带设备号`() {
        assertEquals(
            "http://trade:8080/internal/v1/devices/CAB-001/ota/progress",
            OtaProgressReporter.progressUrl("http://trade:8080/", "CAB-001"),
        )
        assertEquals(
            "https://trade.aicabinet.cn/internal/v1/devices/CAB-9/ota/progress",
            OtaProgressReporter.progressUrl("https://trade.aicabinet.cn", "CAB-9"),
        )
    }

    @Test
    fun `状态取值必须与服务端枚举逐字一致`() {
        // 服务端 ALLOWED_PROGRESS_STATUS 对未知取值直接 400 —— 这里把它钉成判据，
        // 将来谁改了 wire 值（比如写成小写）会立刻红，而不是留到现场才发现进度全丢。
        assertEquals(
            listOf("IDLE", "DOWNLOADING", "INSTALLING", "SUCCESS", "FAILED"),
            OtaUpgradeStatus.values().map { it.wire },
        )
    }
}
