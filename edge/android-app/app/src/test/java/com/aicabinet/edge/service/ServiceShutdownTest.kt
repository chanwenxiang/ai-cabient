package com.aicabinet.edge.service

import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * P0-7（同族）：停机调度 [ServiceShutdown] 的判据。
 *
 * 为什么这一层单测有意义：`CabinetService.onDestroy()` 本身在 JVM 里跑不起来（要真 Service
 * 生命周期 + 真 MQTT broker + 真串口），但**决定成败的那段语义**已经抽成纯函数 ——
 * 「跑在非主线程」「不被业务作用域连带取消」「跑完自毁」三条都能在这里证明。
 * 剩下「onDestroy 里确实这么接线」由静态门禁 `check-edge-queue-threading.mjs` 规则④ 守。
 *
 * ⚠️ 3 号用例是**负向对照**：它证明的不是「当前实现对」，而是**「这个失效模式真实存在」**。
 * 没有它，前两条只能说明「现在恰好过了」，说明不了判据有牙齿。
 */
@RunWith(RobolectricTestRunner::class)
class ServiceShutdownTest {

    private fun ioScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 有界等待「自毁」。不停一下就断言 = 竞态，会随机红。 */
    private fun awaitCancelled(scope: CoroutineScope, timeoutMs: Long = 5_000) {
        val job = scope.coroutineContext[Job] ?: error("作用域必须带 Job，否则 cancel() 无从判定")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!job.isCancelled && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("停机跑完后应把独立作用域自毁（否则每次启停泄漏一个）", job.isCancelled)
    }

    @Test
    fun `停机在非主线程执行_且完成后自毁独立作用域`() {
        val mainThread = Looper.getMainLooper().thread
        val scope = ioScope()
        val ranOn = AtomicReference<Thread>()
        val done = CountDownLatch(1)

        ServiceShutdown.schedule(scope) {
            ranOn.set(Thread.currentThread())
            done.countDown()
        }

        assertTrue("停机必须真的执行，否则本用例是空转", done.await(10, TimeUnit.SECONDS))
        assertNotEquals(
            "停机会阻塞等待 Paho 的 30s quiesce + 串口 close，绝不能在主线程跑",
            mainThread,
            ranOn.get()
        )
        awaitCancelled(scope)
    }

    @Test
    fun `停机抛异常_仍要上报并自毁作用域`() {
        val scope = ioScope()
        val reported = AtomicReference<Throwable>()
        val reportedLatch = CountDownLatch(1)

        ServiceShutdown.schedule(
            scope,
            onError = {
                reported.set(it)
                reportedLatch.countDown()
            },
            stop = { throw IllegalStateException("broker 已断开") }
        )

        assertTrue("停机失败必须被上报（不能静默）", reportedLatch.await(10, TimeUnit.SECONDS))
        assertTrue(
            "上报的应是原异常（失败路径最容易被写成只吞不报）",
            reported.get() is IllegalStateException
        )
        awaitCancelled(scope)
    }

    @Test
    fun `负向对照_作用域已被取消时停机静默不执行`() {
        val scope = ioScope()
        scope.cancel() // 模拟「把停机挂在 serviceScope 上，下一句就是 serviceScope.cancel()」

        val done = CountDownLatch(1)
        ServiceShutdown.schedule(scope) { done.countDown() }

        assertTrue(
            "这就是「挂错作用域」的失效模式：停机不执行，且不抛异常、不打日志。\n" +
                "  CabinetService.onDestroy() 若把 stop() 挂回 serviceScope 就会退化成这样 ——\n" +
                "  服务停了但串口没释放、MQTT 没断开。本用例把它钉死，防止将来被「简化」回去。",
            !done.await(300, TimeUnit.MILLISECONDS)
        )
    }
}
