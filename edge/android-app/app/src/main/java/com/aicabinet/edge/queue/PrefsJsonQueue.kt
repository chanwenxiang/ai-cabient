package com.aicabinet.edge.queue

import android.content.Context
import android.os.Looper
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * E-P1-2：SharedPreferences JSON 持久化队列的公共存储层。
 * MQTT 出站与离线上传共用同一套 load/save/并发锁，避免双实现漂移。
 *
 * P0-7：写路径用同步 `commit()`（要的就是「进程被杀也不丢」的持久性）。代价是**主线程调用会阻塞 UI**。
 * 该类无法替调用方选线程，所以退一步做**可观测**：主线程写会记一条 WARN 并经 [onMainThreadWrite]
 * 上报，使这类回归能被测试/监控抓住，而不是靠人读调用链。
 */
class PrefsJsonQueue<T>(
    context: Context,
    prefsName: String,
    private val key: String = "pending",
    private val typeRef: TypeReference<List<T>>,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    private val tag: String = "PrefsJsonQueue",
    /** 主线程写回调；默认 null（只记 WARN）。测试注入它做**双向**断言。 */
    private val onMainThreadWrite: ((String) -> Unit)? = null
) {
    private val prefs = context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    @Synchronized
    fun size(): Int = loadMutable().size

    @Synchronized
    fun snapshot(): List<T> = loadMutable().toList()

    @Synchronized
    fun replaceAll(items: List<T>) {
        save(items)
    }

    /**
     * 原子读写：在锁内变换队列并写回（同步 `commit()`）。
     * 调用方须在后台线程（MQTT 回调 / 心跳池 / offline-upload 池等）；勿在主线程大批量调用，以免 ANR。
     * 违反时不再无声：[save] 会记 WARN 并触发 [onMainThreadWrite]。
     */
    @Synchronized
    fun mutate(block: (MutableList<T>) -> Unit) {
        val pending = loadMutable()
        block(pending)
        save(pending)
    }

    private fun loadMutable(): MutableList<T> {
        val json = prefs.getString(key, "[]") ?: "[]"
        return runCatching { mapper.readValue(json, typeRef).toMutableList() }
            .onFailure { Log.w(tag, "queue decode failed: ${it.message}") }
            .getOrElse { mutableListOf() }
    }

    private fun save(items: List<T>) {
        // P0-7：commit() 是**同步写盘**。历史上 `CabinetController.start()` 在
        // `CabinetService.onCreate()`（主线程）里跑，connect() 收尾的 `flushOutbound()`
        // 无条件调 `OutboundMqttQueue.drain()` ⇒ 每次 App 启动都把这条 commit 带到主线程。
        // 根因已在 CabinetService 侧移出主线程；这里加检测，防同类回归再次无声发生。
        //
        // ⚠️ 这里**刻意只记不离线**：Looper 相关 API 在非 Robolectric 的纯 JVM 单测里返回默认值
        // （null），此时判定为「不在主线程」——宁可漏报，也不能让存储层反过来炸掉测试。
        if (isOnMainThread()) {
            Log.w(tag, MAIN_THREAD_WRITE_MSG + " key=$key size=${items.size}")
            onMainThreadWrite?.invoke(tag)
        }
        prefs.edit().putString(key, mapper.writeValueAsString(items)).commit()
    }

    companion object {
        /** 日志判据锚点：测试按它筛选 ShadowLog，不要改字面量。 */
        const val MAIN_THREAD_WRITE_MSG = "SharedPreferences commit on MAIN thread"

        internal fun isOnMainThread(): Boolean {
            val current = Looper.myLooper() ?: return false
            return current == Looper.getMainLooper()
        }
    }
}
