package com.aicabinet.edge.mqtt

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `OutboundMqttQueue` 的丢弃/重试策略单测（C21 / H62a）。
 *
 * 为什么必须真跑 SharedPreferences：这个类的判据全部落在**容量与尝试次数**上，
 * 而这两个数字来自 `EdgeRuntimeConfig`（写 prefs）—— 纯 JVM 测不到「现场把上限调小之后
 * 队列真的按新上限丢消息」这条链路。
 *
 * 失效后果（现场很难查）：
 *   - 满队列时不优先丢非关键 ⇒ **关门事件被状态上报挤掉**，交易缺凭证；
 *   - 重试次数算错一格 ⇒ 要么关键信令提前被放弃、要么永远赖在队列里重发；
 *   - 放弃时不回调告警 ⇒ 队列静默残废，没人知道。
 *
 * 容量下界由 `EdgeRuntimeConfig` 夹紧到 **50**、重试下界夹到 **3**（见 `EdgeRuntimeConfigPrefsTest`），
 * 所以本类用 50 / 3 这两个最小值当测试规模，不必推 500 条消息。
 */
@RunWith(RobolectricTestRunner::class)
class OutboundMqttQueueTest {

    private lateinit var ctx: Context

    private val cfgPrefs by lazy { ctx.getSharedPreferences("edge_runtime_config", Context.MODE_PRIVATE) }

    /** 与 `OutboundMqttQueue.PREFS` 同值。字面量钉住：改 prefs 名会让**已上线设备**丢待发消息。 */
    private val queuePrefsName = "outbound_mqtt_queue"

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(queuePrefsName, Context.MODE_PRIVATE).edit().clear().commit()
        cfgPrefs.edit().clear().commit()
    }

    private fun setMaxItems(v: Int) = cfgPrefs.edit().putInt("mqtt_outbound_max_items", v).commit()
    private fun setMaxAttempts(v: Int) = cfgPrefs.edit().putInt("mqtt_outbound_max_attempts", v).commit()

    private fun nonCritical(i: Int) = """{"type":"STATUS","i":$i}"""
    private fun critical(i: Int) = """{"type":"DOOR","i":$i}"""

    private fun enqueue(q: OutboundMqttQueue, payload: String) =
        q.enqueue("cabinet/CAB-001/evt", payload.toByteArray(Charsets.UTF_8), 1)

    /** 把整条队列抽干并收集 payload，用来看「留下了什么、丢了什么」。 */
    private fun drainCollect(q: OutboundMqttQueue): List<String> {
        val seen = mutableListOf<String>()
        q.drain({ m ->
            seen.add(m.payload)
            true
        })
        return seen
    }

    // ---------- 基本进出 ----------

    @Test
    fun `入队后长度递增`() {
        val q = OutboundMqttQueue(ctx)
        assertEquals(0, q.size())
        enqueue(q, nonCritical(1))
        enqueue(q, nonCritical(2))
        assertEquals(2, q.size())
    }

    @Test
    fun `跨实例持久化_模拟进程重启`() {
        enqueue(OutboundMqttQueue(ctx), nonCritical(1))
        // 新实例读同一个 prefs：若没真落盘，这里读到 0 ⇒ 断网期间的待发消息全丢。
        assertEquals(1, OutboundMqttQueue(ctx).size())
    }

    @Test
    fun `drain_全部发送成功则清空`() {
        val q = OutboundMqttQueue(ctx)
        enqueue(q, nonCritical(1))
        enqueue(q, nonCritical(2))
        val seen = drainCollect(q)
        assertEquals(2, seen.size)
        assertEquals(0, q.size())
    }

    @Test
    fun `drain_按入队顺序发送`() {
        val q = OutboundMqttQueue(ctx)
        enqueue(q, nonCritical(1))
        enqueue(q, nonCritical(2))
        enqueue(q, nonCritical(3))
        assertEquals(listOf(nonCritical(1), nonCritical(2), nonCritical(3)), drainCollect(q))
    }

    // ---------- 容量与丢弃优先级 ----------

    @Test
    fun `未达上限时不丢消息`() {
        setMaxItems(50)
        val q = OutboundMqttQueue(ctx)
        repeat(50) { enqueue(q, nonCritical(it)) }
        assertEquals(50, q.size())
        assertEquals(50, drainCollect(q).size)
    }

    @Test
    fun `超过上限时优先丢弃非关键消息_关键门事件必须留下`() {
        setMaxItems(50)
        val q = OutboundMqttQueue(ctx)

        // ⚠️ 队首必须是**关键**消息，否则这条用例守不住任何东西。
        //    第一版把队首塞满了非关键，结果朴素实现「恒丢第 0 条」与
        //    「挑第一条非关键」**结果完全相同** —— 注入漂移后照样绿（形态③）。
        //    2026-09-19 由 ab-drift D10 抓到，故改为「关键打头、非关键随后」。
        enqueue(q, critical(0))
        repeat(49) { enqueue(q, nonCritical(it + 1)) }
        enqueue(q, critical(999)) // 第 51 条：必须挤掉一条**非关键**，而不是队首那个 DOOR

        assertEquals(50, q.size())
        val seen = drainCollect(q)
        assertTrue(
            "队首的关键 DOOR 被挤掉了 —— 「恒丢第 0 条」就会造成这个后果：交易缺凭证",
            seen.contains(critical(0))
        )
        assertTrue("新入队的关键 DOOR 事件没进去", seen.contains(critical(999)))
        assertFalse("应优先丢**最早的非关键**消息", seen.contains(nonCritical(1)))
        assertTrue("只应丢一条", seen.contains(nonCritical(2)))
        assertEquals(50, seen.size)
    }

    @Test
    fun `队列全是关键消息时丢弃最早的一条`() {
        setMaxItems(50)
        val q = OutboundMqttQueue(ctx)
        repeat(50) { enqueue(q, critical(it)) }
        enqueue(q, critical(999))

        assertEquals(50, q.size())
        val seen = drainCollect(q)
        assertTrue(seen.contains(critical(999)))
        // 钉住 `takeIf { it >= 0 } ?: 0` 的**兜底取值是 0（最早）**。
        // 若写成 `?: pending.size - 1`（丢最新），这两条断言会红 —— 由 ab-drift D15 证明。
        assertFalse("无「非关键」可丢时应退化为先进先出丢弃", seen.contains(critical(0)))
        assertTrue("不应连丢两条", seen.contains(critical(1)))
    }

    // ---------- 重试与放弃（边界 ±1） ----------

    @Test
    fun `发送失败时保留消息并在重试上限内不放弃`() {
        setMaxAttempts(3)
        val q = OutboundMqttQueue(ctx)
        enqueue(q, nonCritical(1))

        var abandonCount = 0
        repeat(3) { q.drain({ false }, { abandonCount++ }) }

        // attempts 从 0 起：第 1/2/3 次失败后仍满足 attempts(1/2/3) …… 见下条边界说明。
        assertEquals("3 次失败后仍应留在队列（第 4 次才达到放弃条件）", 1, q.size())
        assertEquals(0, abandonCount)
    }

    @Test
    fun `达到重试上限时放弃并回调告警_恰好一次`() {
        setMaxAttempts(3)
        val q = OutboundMqttQueue(ctx)
        enqueue(q, nonCritical(1))

        var abandonCount = 0
        repeat(4) { q.drain({ false }, { abandonCount++ }) }

        // 判定条件是 `attempts < maxAttempts`：0→1,1→2,2→3 保留；attempts==3 时放弃。
        // 钉住「第 4 次才放弃」这条边界 —— 写成 <= 会让关键信令少重试一轮。
        assertEquals("第 4 次失败后应被放弃", 0, q.size())
        assertEquals("放弃告警必须恰好回调一次", 1, abandonCount)
    }

    @Test
    fun `发送抛异常按失败处理而不是让整轮 drain 崩掉`() {
        setMaxAttempts(3)
        val q = OutboundMqttQueue(ctx)
        enqueue(q, nonCritical(1))

        // publish 抛异常：runCatching().getOrDefault(false) ⇒ 当失败。
        // 若这条路径没兜住，一次网络抖动会让整条队列在后台线程里炸掉、再没人重试。
        q.drain({ throw java.io.IOException("broker reset") }, null)
        assertEquals(1, q.size())

        // 而且剩余消息仍可正常发出（队列没被写坏）。
        assertEquals(1, drainCollect(q).size)
    }

    @Test
    fun `混合结果_成功的移出_失败的留下`() {
        val q = OutboundMqttQueue(ctx)
        enqueue(q, nonCritical(1))
        enqueue(q, nonCritical(2))
        enqueue(q, nonCritical(3))

        q.drain({ m -> m.payload != nonCritical(2) }, null)

        assertEquals(1, q.size())
        assertEquals(listOf(nonCritical(2)), drainCollect(q))
    }

    @Test
    fun `空队列 drain 不回调也不报错`() {
        val q = OutboundMqttQueue(ctx)
        var called = 0
        q.drain({ called++; true }, { called++ })
        assertEquals(0, called)
        assertEquals(0, q.size())
    }
}
