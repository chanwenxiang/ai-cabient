package com.aicabinet.edge.mqtt

import android.content.Context
import android.util.Log
import com.aicabinet.edge.config.EdgeRuntimeConfig
import com.aicabinet.edge.status.DeviceStatusHub
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MqttDeviceClient(
    context: Context,
    private val deviceId: String = EdgeRuntimeConfig.deviceId(context),
    private val broker: String = EdgeRuntimeConfig.mqttBroker(context),
    private val onOpenDoor: (OpenDoorCommand) -> Unit
) : MqttCallback {

    private val mapper = jacksonObjectMapper()
    private val clientId = "edge-$deviceId-${UUID.randomUUID().toString().take(6)}"
    private lateinit var client: MqttClient
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "mqtt-heartbeat-$deviceId").apply { isDaemon = true }
    }

    fun connect() {
        client = MqttClient(broker, clientId, MemoryPersistence())
        val options = MqttConnectOptions().apply {
            isAutomaticReconnect = true
            isCleanSession = true
            connectionTimeout = 10
            keepAliveInterval = 30
        }
        client.setCallback(this)
        client.connect(options)
        client.subscribe("cabinet/$deviceId/cmd", 1)
        publishHeartbeat()
        startHeartbeatLoop()
        DeviceStatusHub.setMqttConnected(true)
        Log.i(TAG, "connected broker=$broker device=$deviceId")
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

    fun publishAck(commandId: String, success: Boolean) {
        val payload = mapper.writeValueAsBytes(mapOf(
            "type" to "ACK",
            "commandId" to commandId,
            "success" to success,
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
        if (!::client.isInitialized || !client.isConnected) return
        val msg = MqttMessage(payload).apply { qos = 1 }
        client.publish(topic, msg)
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
