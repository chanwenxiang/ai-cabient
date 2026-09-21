package com.aicabinet.edge.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * P0-7（同族）：把「停机」从主线程挪出去，并且**必须用独立作用域**。
 *
 * ① 为什么不能同步调：`CabinetService.onDestroy()` 跑在主线程，而 `CabinetController.stop()`
 *    是一条阻塞链 ——
 *      · `offlineQueue.stop()`   → `executor.shutdownNow()`（非阻塞）
 *      · `mqtt.disconnect()`     → Paho 1.2.5 `MqttClient.disconnect()` →
 *                                  `MqttAsyncClient.disconnect(30000L /*QUIESCE_TIMEOUT*/, …)`
 *                                  → `IMqttToken.waitForCompletion()`（**无参 = 无超时等待**）
 *      · `lockDriver.shutdown()` → 串口 `close()`（阻塞 I/O）
 *    ⇒ 主线程最长可卡 30s（quiesce）+ 10s（DISCONNECT_TIMEOUT）+ 串口关闭。
 *
 * ② 为什么**不能**写成 `serviceScope.launch { stop() }`：
 *    `onDestroy()` 的下一句就是 `serviceScope.cancel()`，`cancel()` 会取消**刚提交、
 *    尚未被调度到**的子协程 ⇒ `stop()` 永不执行，而且**不报任何错**。
 *    表现是「服务停了，串口没释放、MQTT 没断开、上传线程还在跑」—— 静默失效比阻塞更难发现。
 *
 * ③ 为什么单独抽成一个对象：这段语义（非主线程 + 不被业务作用域连带取消 + 跑完自毁）
 *    是**可以在 JVM 单测里证明**的（见 `ServiceShutdownTest`）；写在 `onDestroy()` 里
 *    就只能靠人读代码，而这正是本项要还的债。
 */
internal object ServiceShutdown {

    /**
     * 在 [shutdownScope] 上异步执行 [stop]，跑完把该作用域**自毁**（避免泄漏）。
     *
     * @param shutdownScope **独立**作用域。不得传会被 `cancel()` 的业务作用域
     *   （传了不会报错，只会静默不执行 —— `ServiceShutdownTest` 用一条负向用例把这点钉住）。
     * @param onError 停机失败回调；失败也照常自毁，否则作用域泄漏。
     * @param stop 停机动作，内部多为阻塞 I/O，故 [shutdownScope] 应为 `Dispatchers.IO`。
     */
    fun schedule(
        shutdownScope: CoroutineScope,
        onError: (Throwable) -> Unit = {},
        stop: suspend () -> Unit
    ) {
        shutdownScope.launch {
            runCatching { stop() }.onFailure(onError)
            shutdownScope.cancel()
        }
    }
}
