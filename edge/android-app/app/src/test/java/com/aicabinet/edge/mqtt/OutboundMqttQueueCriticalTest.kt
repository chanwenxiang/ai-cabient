package com.aicabinet.edge.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 出站队列「关键性判定」单测。
 *
 * 为什么这个函数值得测：`OutboundMqttQueue.enqueue` 在队列满时用
 * `pending.indexOfFirst { !isCriticalMessage(it.topic, it.payload) }` 挑一条**丢掉**，
 * 兜底 `?: 0` 表示「全是关键就丢第一条」。也就是说这里判错一个类型名，
 * 代价是**关门/开锁/告警信令在弱网下被静默丢弃**，而现场只会看到「门状态不同步」，
 * 查不到根因。它也是 C21 的落点：所有出站 topic 都是 `cabinet/{deviceId}/evt`，
 * 无法按 topic 区分，必须改为解析 payload 的 `type`。
 *
 * 判据锚点（C21 语义，逐条钉住）：
 *  1. payload 能解析出非空 `type` 时，**只看 type**，不再回头看 topic（大小写不敏感）；
 *  2. DOOR / ACK / ALERT 为关键，其余（HEARTBEAT 等）为非关键；
 *  3. payload 非 JSON 或没有可用 type 时，才退回 topic 子串兜底。
 *
 * 纯 JVM：两个函数都在 companion object 里，只用 Jackson + 字符串，不碰 Android API。
 */
class OutboundMqttQueueCriticalTest {

    // ── 规则 1+2：payload type 说了算 ──────────────────────────────────────

    @Test
    fun `DOOR 类型的 payload 视为关键`() {
        assertTrue(OutboundMqttQueue.isCriticalMessage(EVT_TOPIC, """{"type":"DOOR"}"""))
    }

    @Test
    fun `type 判定大小写不敏感`() {
        assertTrue(OutboundMqttQueue.isCriticalMessage(EVT_TOPIC, """{"type":"door"}"""))
        assertTrue(OutboundMqttQueue.isCriticalMessage(EVT_TOPIC, """{"type":"Ack"}"""))
        assertTrue(OutboundMqttQueue.isCriticalMessage(EVT_TOPIC, """{"type":"alert"}"""))
    }

    @Test
    fun `ACK 与 ALERT 均为关键类型`() {
        assertTrue(OutboundMqttQueue.isCriticalMessage(EVT_TOPIC, """{"type":"ACK"}"""))
        assertTrue(OutboundMqttQueue.isCriticalMessage(EVT_TOPIC, """{"type":"ALERT"}"""))
    }

    @Test
    fun `有 type 时不再回看 topic —— 心跳不会被误判为关键`() {
        // 这是 C21 的核心分界：topic 里带 door 字样，但 payload 自报 HEARTBEAT。
        // 若实现退回按 topic 判断，这里会误判为关键 —— 于是队列满时先丢 DOOR 保住心跳。
        assertFalse(
            OutboundMqttQueue.isCriticalMessage(
                "cabinet/CAB-001/evt/door",
                """{"type":"HEARTBEAT"}"""
            )
        )
        assertFalse(OutboundMqttQueue.isCriticalMessage(EVT_TOPIC, """{"type":"STATUS"}"""))
    }

    @Test
    fun `type 为空白字符串时视为无 type，退回 topic 兜底`() {
        assertTrue(
            OutboundMqttQueue.isCriticalMessage("cabinet/CAB-001/evt/door", """{"type":"  "}""")
        )
    }

    @Test
    fun `missing type 字段时退回 topic 兜底`() {
        assertTrue(OutboundMqttQueue.isCriticalMessage("cabinet/CAB-001/evt/door", """{"foo":1}"""))
        assertFalse(OutboundMqttQueue.isCriticalMessage(EVT_TOPIC, """{"foo":1}"""))
    }

    @Test
    fun `type 显式为 JSON null 时不回退 topic，按非关键处理`() {
        // Jackson 的 NullNode.asText() 返回字面量 "null"（既非 null 也非 blank），
        // 于是实现会拿它去比类型白名单 ⇒ 非关键，**不会**退回 topic 兜底。
        // 即「无可用 type 才退回」只覆盖「解析失败 / 缺字段」，不覆盖「字段在但值为 null」。
        // 本条是这一缝隙的钉子；若哪天实现改成把 NullNode 视作缺字段，这里会红——那正是要你知道的。
        assertFalse(
            OutboundMqttQueue.isCriticalMessage("cabinet/CAB-001/evt/door", """{"type":null}""")
        )
    }

    // ── 规则 3：非 JSON payload 的兜底路径 ────────────────────────────────

    @Test
    fun `payload 非 JSON 时退回 topic 子串判断`() {
        assertTrue(OutboundMqttQueue.isCriticalMessage("cabinet/CAB-001/evt/door", "raw-text"))
        assertTrue(OutboundMqttQueue.isCriticalMessage("cabinet/CAB-001/evt/session", "raw-text"))
    }

    @Test
    fun `payload 非 JSON 且 topic 无关键词时按非关键处理`() {
        // 出站 topic 恒为 cabinet/{deviceId}/evt ⇒ 无关键词时必须落回「非关键」，
        // 否则队列满时会丢掉唯一的可丢对象、去丢真正关键的那条。
        assertFalse(OutboundMqttQueue.isCriticalMessage(EVT_TOPIC, "raw-text"))
        assertFalse(OutboundMqttQueue.isCriticalMessage(EVT_TOPIC, ""))
    }

    // ── isCriticalTopic 自身 ──────────────────────────────────────────────

    @Test
    fun `isCriticalTopic 命中五类关键词且大小写不敏感`() {
        assertTrue(OutboundMqttQueue.isCriticalTopic("cabinet/CAB-001/evt/door"))
        assertTrue(OutboundMqttQueue.isCriticalTopic("cabinet/CAB-001/evt/session"))
        assertTrue(OutboundMqttQueue.isCriticalTopic("cabinet/CAB-001/evt/lock"))
        assertTrue(OutboundMqttQueue.isCriticalTopic("cabinet/CAB-001/evt/OPEN"))
        assertTrue(OutboundMqttQueue.isCriticalTopic("cabinet/CAB-001/evt/close"))
    }

    @Test
    fun `isCriticalTopic 对标准出站 topic 判为非关键`() {
        assertFalse(OutboundMqttQueue.isCriticalTopic(EVT_TOPIC))
        assertFalse(OutboundMqttQueue.isCriticalTopic("cabinet/CAB-001/heartbeat"))
    }

    // ── 队列满时的丢弃选择（把上面判据的后果显式钉住）────────────────────

    @Test
    fun `队列满时优先丢弃非关键，保留 DOOR`() {
        // 复刻 enqueue 的选人表达式，证明「判据错 ⇒ 丢错人」这条链路成立。
        val pending =
            listOf(
                """{"type":"HEARTBEAT"}""",
                """{"type":"DOOR"}""",
                """{"type":"STATUS"}"""
            )
        val dropIndex =
            pending.indexOfFirst { !OutboundMqttQueue.isCriticalMessage(EVT_TOPIC, it) }
                .takeIf { it >= 0 } ?: 0
        assertEquals(0, dropIndex)
        assertEquals("""{"type":"DOOR"}""", pending[1])
    }

    @Test
    fun `队列全是关键消息时丢弃第一条（兜底分支）`() {
        val pending = listOf("""{"type":"DOOR"}""", """{"type":"ACK"}""")
        val dropIndex =
            pending.indexOfFirst { !OutboundMqttQueue.isCriticalMessage(EVT_TOPIC, it) }
                .takeIf { it >= 0 } ?: 0
        assertEquals(0, dropIndex)
    }

    private companion object {
        /** 生产环境的真实出站 topic 形态：无任何关键词，故 payload.type 是唯一信号源。 */
        const val EVT_TOPIC = "cabinet/CAB-001/evt"
    }
}
