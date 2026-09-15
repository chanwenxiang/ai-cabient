package com.aicabinet.edge.queue

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

/**
 * E-P1-2：SharedPreferences JSON 持久化队列的公共存储层。
 * MQTT 出站与离线上传共用同一套 load/save/并发锁，避免双实现漂移。
 */
class PrefsJsonQueue<T>(
    context: Context,
    prefsName: String,
    private val key: String = "pending",
    private val typeRef: TypeReference<List<T>>,
    private val mapper: ObjectMapper = jacksonObjectMapper(),
    private val tag: String = "PrefsJsonQueue"
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
     * 原子读写：在锁内变换队列并写回。
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
        prefs.edit().putString(key, mapper.writeValueAsString(items)).apply()
    }
}
