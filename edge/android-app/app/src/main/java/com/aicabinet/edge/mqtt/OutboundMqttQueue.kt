package com.aicabinet.edge.mqtt

import android.content.Context
import android.util.Log
import com.aicabinet.edge.config.EdgeRuntimeConfig
import com.aicabinet.edge.queue.PrefsJsonQueue
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper

data class PendingMqttMessage(
    val topic: String,
    val payload: String,
    val qos: Int = 1,
    val enqueuedAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0
)

class OutboundMqttQueue(context: Context) {
    private val appContext = context.applicationContext
    private val store = PrefsJsonQueue(
        context = appContext,
        prefsName = PREFS,
        typeRef = object : TypeReference<List<PendingMqttMessage>>() {},
        tag = TAG
    )

    @Synchronized
    fun enqueue(topic: String, payload: ByteArray, qos: Int = 1) {
        store.mutate { pending ->
            val maxItems = EdgeRuntimeConfig.mqttOutboundMaxItems(appContext)
            if (pending.size >= maxItems) {
                val dropIndex = pending.indexOfFirst { !isCriticalMessage(it.topic, it.payload) }
                    .takeIf { it >= 0 } ?: 0
                val dropped = pending.removeAt(dropIndex)
                Log.w(
                    TAG,
                    "mqtt queue full (max=$maxItems), dropped topic=${dropped.topic} " +
                        "critical=${isCriticalMessage(dropped.topic, dropped.payload)}"
                )
            }
            pending.add(PendingMqttMessage(topic, String(payload, Charsets.UTF_8), qos))
        }
    }

    @Synchronized
    fun drain(
        publish: (PendingMqttMessage) -> Boolean,
        onAbandon: ((PendingMqttMessage) -> Unit)? = null
    ) {
        val maxAttempts = EdgeRuntimeConfig.mqttOutboundMaxAttempts(appContext)
        store.mutate { pending ->
            val remaining = mutableListOf<PendingMqttMessage>()
            for (message in pending) {
                val sent = runCatching { publish(message) }.getOrDefault(false)
                if (!sent) {
                    if (message.attempts < maxAttempts) {
                        remaining.add(message.copy(attempts = message.attempts + 1))
                    } else {
                        Log.e(TAG, "mqtt message abandoned topic=${message.topic}")
                        // H62a: 达到放弃上限时回调告警（发布失败由调用方仅记日志，防循环）
                        runCatching { onAbandon?.invoke(message) }
                            .onFailure { Log.w(TAG, "abandon alert failed: ${it.message}") }
                    }
                }
            }
            pending.clear()
            pending.addAll(remaining)
        }
    }

    @Synchronized
    fun size(): Int = store.size()

    companion object {
        private const val TAG = "OutboundMqttQueue"
        private const val PREFS = "outbound_mqtt_queue"
        private val MAPPER = ObjectMapper()
        private val CRITICAL_TYPES = setOf("DOOR", "ACK", "ALERT")

        /**
         * C21: 所有出站 topic 均为 cabinet/{deviceId}/evt，无法按 topic 区分关键性。
         * 改为解析 payload 的 "type" 字段（大小写不敏感）：DOOR/ACK/ALERT 视为关键；
         * payload 非 JSON/无 type 时退回 [isCriticalTopic] topic 子串兜底，按非关键处理。
         */
        fun isCriticalMessage(topic: String, payload: String): Boolean {
            val type = runCatching { MAPPER.readTree(payload).get("type")?.asText() }.getOrNull()
            if (!type.isNullOrBlank()) {
                return type.uppercase() in CRITICAL_TYPES
            }
            return isCriticalTopic(topic)
        }

        /** 开门/关门/会话事件优先保留，避免队列满时丢关键信令。（C21 后仅作非 JSON payload 兜底） */
        fun isCriticalTopic(topic: String): Boolean {
            val t = topic.lowercase()
            return t.contains("door") ||
                t.contains("session") ||
                t.contains("lock") ||
                t.contains("open") ||
                t.contains("close")
        }
    }
}
