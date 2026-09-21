package com.aicabinet.edge.queue

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.fasterxml.jackson.core.type.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.util.Collections

/**
 * P0-7 子项①：`PrefsJsonQueue` 的**主线程 commit** 风险。
 *
 * 背景（真缺陷，不是理论风险）：`CabinetService.onCreate()`（Android 保证跑在主线程）
 * 曾直接同步调用 `CabinetController.start()`，其中 `MqttDeviceClient.connect()` 收尾的
 * `flushOutbound()` **无条件**调用 `OutboundMqttQueue.drain()` ⇒ `PrefsJsonQueue.save()`
 * 的同步 `commit()` 落在主线程上。根因已在 `CabinetService` 侧移出主线程；
 * 本类把存储层的**可观测性**钉住，让同类回归能被判据抓住。
 *
 * 判据必须**双向**（否则是装饰）：
 *   - 主线程写 ⇒ **必须**上报（否则回归无人发现）；
 *   - 后台线程写 ⇒ **必须不**上报（否则判据恒真、永远绿）；
 *   - 两次都断言数据**真的落盘**（否则「检测到」可以靠「压根没写」换来）。
 */
@RunWith(RobolectricTestRunner::class)
class PrefsJsonQueueMainThreadTest {

    private lateinit var ctx: Context

    private val prefsName = "test_main_thread_write"
    private val tag = "PrefsJsonQueueMainThreadTest"
    private val typeRef = object : TypeReference<List<String>>() {}

    private fun newQueue(onWrite: ((String) -> Unit)? = null) = PrefsJsonQueue(
        context = ctx,
        prefsName = prefsName,
        key = "pending",
        typeRef = typeRef,
        tag = tag,
        onMainThreadWrite = onWrite
    )

    /** 读回**默认生产路径**（不注入回调时）真正打出的那条 WARN。 */
    private fun mainThreadWriteWarnings() = ShadowLog.getLogsForTag(tag).filter {
        it.msg?.startsWith(PrefsJsonQueue.MAIN_THREAD_WRITE_MSG) == true
    }

    @Before
    fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit().clear().commit()
        ShadowLog.clear()
    }

    @Test
    fun `主线程写_两条写路径都被检测到_且数据真的落盘`() {
        assertTrue(
            "前置条件：本用例必须跑在主线程，否则测的是别的东西",
            Looper.myLooper() == Looper.getMainLooper()
        )
        val hits = mutableListOf<String>()
        val queue = newQueue { hits.add(it) }

        queue.replaceAll(listOf("a")) // 写路径①（OfflineUploadQueue 走这条）
        queue.mutate { pending -> pending.add("b") } // 写路径②（OutboundMqttQueue 走这条）

        assertEquals("两条写路径各应上报一次", 2, hits.size)
        assertEquals("默认路径也必须打出 WARN（不依赖注入的回调）", 2, mainThreadWriteWarnings().size)
        // 反「空转假绿」：告警不能是靠「压根没写」换来的。
        assertEquals(listOf("a", "b"), queue.snapshot())
    }

    @Test
    fun `后台线程写_不得误报_否则判据恒真`() {
        val hits = Collections.synchronizedList(mutableListOf<String>())
        val queue = newQueue { hits.add(it) }
        val ran = java.util.concurrent.atomic.AtomicBoolean(false)

        val worker = Thread {
            queue.mutate { pending -> pending.add("bg") }
            ran.set(true)
        }
        worker.start()
        worker.join(10_000)

        assertTrue("后台线程必须真的跑完，否则本用例是空转", ran.get())
        assertEquals(listOf("bg"), queue.snapshot())
        assertEquals("后台线程写不得上报主线程告警", 0, hits.size)
        assertEquals("后台线程写不得产生主线程告警日志", 0, mainThreadWriteWarnings().size)
    }
}
