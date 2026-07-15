package com.aicabinet.edge.mqtt

import android.content.Context
import android.util.Log
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
    private val mapper = jacksonObjectMapper()
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun enqueue(topic: String, payload: ByteArray, qos: Int = 1) {
        val pending = loadMutable()
        if (pending.size >= MAX_ITEMS) {
            pending.removeAt(0)
            Log.w(TAG, "mqtt queue full, dropped oldest message")
        }
        pending.add(PendingMqttMessage(topic, String(payload, Charsets.UTF_8), qos))
        save(pending)
    }

    @Synchronized
    fun drain(publish: (PendingMqttMessage) -> Boolean) {
        val remaining = mutableListOf<PendingMqttMessage>()
        for (message in loadMutable()) {
            val sent = runCatching { publish(message) }.getOrDefault(false)
            if (!sent) {
                if (message.attempts < MAX_ATTEMPTS) {
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
        private const val MAX_ITEMS = 500
        private const val MAX_ATTEMPTS = 200
    }
}
