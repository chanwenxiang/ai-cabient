package com.aicabinet.edge.mqtt

import android.content.Context
import android.util.Log
import com.aicabinet.edge.config.EdgeRuntimeConfig
import com.aicabinet.edge.status.DeviceStatusHub
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MqttDeviceClient(
    context: Context,
    private val deviceId: String = EdgeRuntimeConfig.ensureDeviceId(context),
    private val broker: String = EdgeRuntimeConfig.mqttBroker(context),
    private val onOpenDoor: (OpenDoorCommand) -> Unit
) : MqttCallbackExtended {

    private val appContext = context.applicationContext
    private val mapper = jacksonObjectMapper()
    // clientId 必须等于 deviceId：EMQX 文件授权器按 cabinet/${clientid}/# 做设备级隔离，
    // 任何前缀（如旧的 edge-）都会被 no_match_action=deny 拒绝。
    private val clientId = deviceId
    private val outboundQueue = OutboundMqttQueue(appContext)
    private lateinit var client: MqttClient
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mqtt-heartbeat-$deviceId").apply { isDaemon = true }
    }

    fun connect() {
        val persistenceDir = File(appContext.filesDir, "mqtt-paho/$deviceId").apply { mkdirs() }
        val useTls = EdgeRuntimeConfig.mqttUseTls(appContext)
        val resolvedBroker = normalizeBroker(broker, useTls)
        client = MqttClient(resolvedBroker, clientId, MqttDefaultFilePersistence(persistenceDir.absolutePath))
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = false
            connectionTimeout = 10
            keepAliveInterval = 30
            val username = EdgeRuntimeConfig.mqttUsername(appContext)
            val password = EdgeRuntimeConfig.mqttPassword(appContext)
            if (username.isNotBlank()) {
                userName = username
            }
            if (password.isNotBlank()) {
                this.password = password.toCharArray()
            }
            if (useTls || resolvedBroker.startsWith("ssl://")) {
                socketFactory = MqttSslSocketFactories.create(
                    trustStorePath = EdgeRuntimeConfig.mqttTrustStorePath(appContext),
                    trustStorePassword = EdgeRuntimeConfig.mqttTrustStorePassword(appContext),
                    trustStoreType = EdgeRuntimeConfig.mqttTrustStoreType(appContext),
                    keyStorePath = EdgeRuntimeConfig.mqttKeyStorePath(appContext),
                    keyStorePassword = EdgeRuntimeConfig.mqttKeyStorePassword(appContext),
                    keyStoreType = EdgeRuntimeConfig.mqttKeyStoreType(appContext),
                    strictCustomTrust = EdgeRuntimeConfig.mqttTlsStrict(appContext)
                )
            }
        }
        client.setCallback(this)
        client.connect(options)
        subscribeCommands()
        publishHeartbeat()
        flushOutbound()
        startHeartbeatLoop()
        DeviceStatusHub.setMqttConnected(true)
        Log.i(TAG, "connected broker=$resolvedBroker device=$deviceId tls=$useTls")
    }

    private fun normalizeBroker(raw: String, useTls: Boolean): String {
        val trimmed = raw.trim()
        if (!useTls) return trimmed
        return when {
            trimmed.startsWith("ssl://") -> trimmed
            trimmed.startsWith("tcp://") -> "ssl://" + trimmed.removePrefix("tcp://")
            trimmed.startsWith("mqtt://") -> "ssl://" + trimmed.removePrefix("mqtt://")
            else -> trimmed
        }
    }

    fun disconnect() {
        heartbeatExecutor.shutdownNow()
        if (::client.isInitialized && client.isConnected) {
            client.disconnect()
        }
        DeviceStatusHub.setMqttConnected(false)
    }

    fun publishDoorEvent(
        sessionId: String,
        doorState: String,
        videoUri: String? = null,
        uploadStatus: String? = null,
        videoClipsJson: String? = null,
        cameraFusionMode: String? = null
    ) {
        val data = mutableMapOf<String, Any>(
            "type" to "DOOR",
            "sessionId" to sessionId,
            "doorState" to doorState,
            "timestamp" to System.currentTimeMillis()
        )
        if (videoUri != null) data["videoUri"] = videoUri
        if (uploadStatus != null) data["uploadStatus"] = uploadStatus
        if (videoClipsJson != null) data["videoClipsJson"] = videoClipsJson
        if (cameraFusionMode != null) data["cameraFusionMode"] = cameraFusionMode
        publish("cabinet/$deviceId/evt", mapper.writeValueAsBytes(data))
    }

    fun publishAck(commandId: String, success: Boolean, message: String? = null) {
        val data = mutableMapOf<String, Any>(
            "type" to "ACK",
            "commandId" to commandId,
            "success" to success,
            "timestamp" to System.currentTimeMillis()
        )
        if (message != null) data["message"] = message
        publish("cabinet/$deviceId/evt", mapper.writeValueAsBytes(data))
    }

    /** H62a: 边缘侧告警事件（如队列放弃），未连接时经 OutboundMqttQueue 持久化补投。 */
    fun publishAlert(alertType: String, message: String) {
        val payload = mapper.writeValueAsBytes(mapOf(
            "type" to "ALERT",
            "alertType" to alertType,
            "message" to message,
            "timestamp" to System.currentTimeMillis()
        ))
        publish("cabinet/$deviceId/evt", payload)
    }

    private fun startHeartbeatLoop() {
        heartbeatExecutor.scheduleAtFixedRate({
            try {
                publishHeartbeat()
            } catch (e: Exception) {
                Log.w(TAG, "heartbeat failed: ${e.message}")
            }
        }, 30, 30, TimeUnit.SECONDS)
    }

    private fun publishHeartbeat() {
        val payload = mapper.writeValueAsBytes(mapOf(
            "type" to "HEARTBEAT",
            "deviceId" to deviceId,
            "timestamp" to System.currentTimeMillis(),
            "appVersion" to com.aicabinet.edge.BuildConfig.VERSION_NAME,
            "firmwareVersion" to com.aicabinet.edge.BuildConfig.VERSION_NAME
        ))
        publish("cabinet/$deviceId/evt", payload)
    }

    private fun publish(topic: String, payload: ByteArray) {
        if (!::client.isInitialized || !client.isConnected) {
            outboundQueue.enqueue(topic, payload, 1)
            DeviceStatusHub.setMqttConnected(false)
            return
        }
        if (!publishNow(topic, payload, 1)) {
            outboundQueue.enqueue(topic, payload, 1)
        }
    }

    private fun publishNow(topic: String, payload: ByteArray, qos: Int): Boolean {
        if (!::client.isInitialized || !client.isConnected) return false
        val msg = MqttMessage(payload).apply { qos = 1 }
        return runCatching {
            msg.qos = qos
            client.publish(topic, msg)
            true
        }.getOrElse {
            Log.w(TAG, "publish failed topic=$topic: ${it.message}")
            false
        }
    }

    private fun flushOutbound() {
        if (!::client.isInitialized || !client.isConnected) return
        val pending = outboundQueue.size()
        if (pending > 0) {
            Log.i(TAG, "flushing mqtt queue size=$pending")
        }
        outboundQueue.drain(
            publish = { message ->
                publishNow(message.topic, message.payload.toByteArray(Charsets.UTF_8), message.qos)
            },
            // H62a: 消息达到放弃上限时直发告警；用 publishNow 不走 publish()，失败仅日志，防再入队成环
            onAbandon = { message ->
                val alert = mapper.writeValueAsBytes(mapOf(
                    "type" to "ALERT",
                    "alertType" to "EDGE_QUEUE_ABANDON",
                    "message" to "mqtt outbound message abandoned topic=${message.topic} attempts=${message.attempts}",
                    "timestamp" to System.currentTimeMillis()
                ))
                if (!publishNow("cabinet/$deviceId/evt", alert, 1)) {
                    Log.w(TAG, "abandon alert publish failed, dropped to avoid queue loop")
                }
            }
        )
    }

    private fun subscribeCommands() {
        client.subscribe("cabinet/$deviceId/cmd", 1)
    }

    override fun connectComplete(reconnect: Boolean, serverURI: String?) {
        DeviceStatusHub.setMqttConnected(true)
        runCatching { subscribeCommands() }
            .onFailure { Log.w(TAG, "subscribe after reconnect failed: ${it.message}") }
        flushOutbound()
    }

    override fun connectionLost(cause: Throwable?) {
        Log.w(TAG, "connection lost", cause)
        DeviceStatusHub.setMqttConnected(false)
    }

    override fun messageArrived(topic: String?, message: MqttMessage?) {
        val body = message?.payload ?: return
        try {
            val node: Map<String, Any> = mapper.readValue(body)
            if (node["type"] == "OPEN_DOOR") {
                val expireAt = (node["expireAt"] as? Number)?.toLong()
                if (expireAt != null && System.currentTimeMillis() > expireAt) {
                    Log.w(TAG, "OPEN_DOOR expired commandId=${node["commandId"]}")
                    // H60: 过期指令不执行开门，但仍需回执失败，避免云端一直等待结果
                    (node["commandId"] as? String)?.let { publishAck(it, false, "command expired") }
                    return
                }
                val cmd = OpenDoorCommand(
                    commandId = node["commandId"] as String,
                    sessionId = node["sessionId"] as String,
                    userId = (node["userId"] as Number).toLong(),
                    operatorMode = node["operatorMode"] as? Boolean ?: false
                )
                onOpenDoor(cmd)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handle message failed", e)
            DeviceStatusHub.setError("MQTT 消息处理失败")
        }
    }

    override fun deliveryComplete(token: IMqttDeliveryToken?) {}

    data class OpenDoorCommand(
        val commandId: String,
        val sessionId: String,
        val userId: Long,
        val operatorMode: Boolean
    )

    companion object {
        private const val TAG = "MqttDeviceClient"
    }
}
