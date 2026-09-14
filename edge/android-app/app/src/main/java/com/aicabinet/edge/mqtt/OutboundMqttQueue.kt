package com.aicabinet.edge.mqtt

import android.content.Context
import android.util.Log
import com.aicabinet.edge.config.EdgeRuntimeConfig
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

data class PendingMqttMessage(
    val topic: String,
    val payload: String,
    val qos: Int = 1,
    val enqueuedAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0
)

class OutboundMqttQueue(context: Context) {
    private val appContext = context.applicationContext
    private val mapper = jacksonObjectMapper()
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(topic: String, payload: ByteArray, qos: Int = 1) {
        val pending = loadMutable()
        val maxItems = EdgeRuntimeConfig.mqttOutboundMaxItems(appContext)
        if (pending.size >= maxItems) {
            val dropIndex = pending.indexOfFirst { !isCriticalTopic(it.topic) }.takeIf { it >= 0 } ?: 0
            val dropped = pending.removeAt(dropIndex)
            Log.w(
                TAG,
                "mqtt queue full (max=$maxItems), dropped topic=${dropped.topic} critical=${isCriticalTopic(dropped.topic)}"
            )
        }
        pending.add(PendingMqttMessage(topic, String(payload, Charsets.UTF_8), qos))
        save(pending)
    }

    @Synchronized
    fun drain(publish: (PendingMqttMessage) -> Boolean) {
        val maxAttempts = EdgeRuntimeConfig.mqttOutboundMaxAttempts(appContext)
        val remaining = mutableListOf<PendingMqttMessage>()
        for (message in loadMutable()) {
            val sent = runCatching { publish(message) }.getOrDefault(false)
            if (!sent) {
                if (message.attempts < maxAttempts) {
                    remaining.add(message.copy(attempts = message.attempts + 1))
                } else {
                    Log.e(TAG, "mqtt message abandoned topic=${message.topic}")
                }
            }
        }
        save(remaining)
    }

    @Synchronized
    fun size(): Int = loadMutable().size

    private fun loadMutable(): MutableList<PendingMqttMessage> {
        val json = prefs.getString(KEY_QUEUE, "[]") ?: "[]"
        return runCatching { mapper.readValue<List<PendingMqttMessage>>(json).toMutableList() }
            .getOrElse { mutableListOf() }
    }

    private fun save(items: List<PendingMqttMessage>) {
        prefs.edit().putString(KEY_QUEUE, mapper.writeValueAsString(items)).apply()
    }

    companion object {
        private const val TAG = "OutboundMqttQueue"
        private const val PREFS = "outbound_mqtt_queue"
        private const val KEY_QUEUE = "pending"

        /** 开门/关门/会话事件优先保留，避免队列满时丢关键信令。 */
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
