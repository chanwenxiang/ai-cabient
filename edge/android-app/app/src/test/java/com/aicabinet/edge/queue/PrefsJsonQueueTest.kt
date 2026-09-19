package com.aicabinet.edge.queue

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.fasterxml.jackson.core.type.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * `PrefsJsonQueue` 的 **真 SharedPreferences** 持久化单测（E-P1-2）。
 *
 * 这个类是 MQTT 出站队列与离线上传队列的**共同存储层**，它的失效方式全都很难查：
 *   - 读不到旧值 ⇒ 重启后待发消息凭空消失，现场表现为「偶发丢门事件」；
 *   - 反序列化崩溃 ⇒ 整条队列抛异常，比丢一条更糟（离线补传彻底停摆）；
 *   - `snapshot()` 返回内部引用 ⇒ 调用方顺手改一下就无声改坏队列。
 *
 * 本类只测存储层语义；**丢弃/重试策略**在 `OutboundMqttQueueTest` 里测。
 */
@RunWith(RobolectricTestRunner::class)
class PrefsJsonQueueTest {

    private lateinit var ctx: Context

    private val prefsName = "test_prefs_json_queue"
    private val typeRef = object : TypeReference<List<String>>() {}

    private fun newQueue(key: String = "pending") =
        PrefsJsonQueue(ctx, prefsName, key, typeRef, tag = "PrefsJsonQueueTest")

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `未写入过时为空队列`() {
        assertEquals(0, newQueue().size())
        assertEquals(emptyList<String>(), newQueue().snapshot())
    }

    @Test
    fun `替换后能读回`() {
        newQueue().replaceAll(listOf("a", "b", "c"))
        assertEquals(listOf("a", "b", "c"), newQueue().snapshot())
        assertEquals(3, newQueue().size())
    }

    @Test
    fun `跨实例持久化_模拟进程重启`() {
        newQueue().replaceAll(listOf("first", "second"))
        // 新实例、同一个 prefs：若存储没真落盘，这里会读成空 ⇒ 出站消息静默丢失。
        assertEquals(listOf("first", "second"), newQueue().snapshot())
    }

    @Test
    fun `replaceAll_空列表清空队列`() {
        val q = newQueue()
        q.replaceAll(listOf("x"))
        q.replaceAll(emptyList())
        assertEquals(0, newQueue().size())
    }

    @Test
    fun `mutate_变换结果被持久化`() {
        val q = newQueue()
        q.replaceAll(listOf("a"))
        q.mutate { pending ->
            pending.add("b")
            pending.removeAll { it == "a" }
        }
        assertEquals(listOf("b"), newQueue().snapshot())
    }

    @Test
    fun `mutate_块抛异常时不应把半成品写回`() {
        val q = newQueue()
        q.replaceAll(listOf("keep-me"))
        runCatching {
            q.mutate { pending ->
                pending.clear()
                throw IllegalStateException("boom")
            }
        }
        // save() 在 block 之后：block 抛异常则根本不执行 save ⇒ 原值必须完好。
        assertEquals(listOf("keep-me"), newQueue().snapshot())
    }

    @Test
    fun `snapshot_不受后续写入影响`() {
        // 不写成 `q.snapshot().toMutableList().clear()` —— 那样调用方自己就复制了一份，
        // 无论实现是否隔离**都必然通过**，是恒真判据（§11.6 形态③）。
        // 正确的形态：取完快照再改存储，看快照有没有跟着变。
        val q = newQueue()
        q.replaceAll(listOf("a", "b"))
        val snap = q.snapshot()
        q.replaceAll(listOf("z"))
        assertEquals(listOf("a", "b"), snap)
        assertEquals(listOf("z"), q.snapshot())
    }

    @Test
    fun `replaceAll_不与调用方列表共享引用`() {
        // 调用方传进来的 list 之后被自己改：存储里不应跟着变。
        val q = newQueue()
        val src = mutableListOf("a")
        q.replaceAll(src)
        src.add("b")
        assertEquals(listOf("a"), newQueue().snapshot())
    }

    @Test
    fun `损坏JSON_退化为空队列而不是抛异常`() {
        // 现场诱因：写盘中途掉电 / 人手改 prefs.xml / 旧版本写入了不兼容结构。
        // 契约是「丢内容但服务继续」，不是「整条队列炸掉」。
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString("pending", "{ this is not a json array ][")
            .commit()
        assertEquals(0, newQueue().size())
        assertTrue(newQueue().snapshot().isEmpty())
    }

    @Test
    fun `类型不匹配的JSON_同样退化为空队列`() {
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putString("pending", """{"a":1}""")
            .commit()
        assertEquals(0, newQueue().size())
    }

    @Test
    fun `同一prefs下不同key互不干扰`() {
        newQueue("pending").replaceAll(listOf("one"))
        newQueue("other").replaceAll(listOf("two", "three"))
        assertEquals(listOf("one"), newQueue("pending").snapshot())
        assertEquals(listOf("two", "three"), newQueue("other").snapshot())
    }
}
