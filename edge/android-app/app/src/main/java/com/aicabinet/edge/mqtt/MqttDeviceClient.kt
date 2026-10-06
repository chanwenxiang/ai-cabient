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
    private val onOpenDoor: (OpenDoorCommand) -> Unit,
    /**
     * V308：运维指令执行器（LOCK / UNLOCK / REBOOT / SELF_TEST）。
     *
     * <p>🔴 **为什么用回调而不是直接依赖 HAL**：本类只管「解析 + 回ACK + 派发」，
     * 不该知道锁驱动是 `ChzhLockDriver`（串口）还是 `MockLockDriver`（演示）——
     * 那属于 {@code CabinetController} 的组装职责。默认空实现 = 未装配时**明确回失败**，
     * 而<b>不是静默丢弃</b>（静默丢弃会让云端等满15 秒超时才知道）。
     */
    private val onOpsCommand: (OpsCommand) -> Unit = { ops ->
        Log.w(TAG, "ops command not wired: type=${ops.type} commandId=${ops.commandId}")
        publishAck(ops.commandId, false, "运维指令执行器未装配")
    }
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

    /**
     * 运维指令的统一载体。
     *
     * <p>字段与云端 `MqttCommandPublisher.publishOpsCommand` 的报文一一对应：
     * `commandId` / `type` / `expireAt`。
     *
     * @param targetTempC 仅 `SET_TARGET_TEMP` 用；其余指令为 null
     */
    data class OpsCommand(
        val commandId: String,
        val type: String,
        val expireAt: Long?,
        val targetTempC: Int? = null
    ) {
        fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
            expireAt != null && nowMs > expireAt
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

    /**
     * V308：设备自检结构化结果上报（独立于 ACK 事件）。
     *
     * <p>🔴 <b>为什么与 ACK 分开</b>：ACK 的 message 是**给人看的文本**，后台无法聚合；
     * 自检结果是**给机器看的指标**（可用存储、运行时长、门状态、RSSI），
     * 后台要按时间序列看趋势、设阈值告警。混在 ACK 里就只能靠正则解析文本。
     *
     * <p>`items` 里允许 `null` 值（如读不到 RSSI）—— 序列化时保留为 JSON `null`，
     * **不删字段**：后台据此区分「这项没测」与「这项为 0」。
     */
    fun publishSelfTestReport(commandId: String, items: Map<String, Any?>, errors: List<String>) {
        val payload = mapper.writeValueAsBytes(mapOf(
            "type" to "SELF_TEST_REPORT",
            "commandId" to commandId,
            "deviceId" to deviceId,
            "items" to items,
            "errors" to errors,
            "healthy" to errors.isEmpty(),
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
        // 必须写 `this.qos`：裸写 `qos = 1` 会解析到**外层函数参数** `publishNow(..., qos: Int)`，
        // 函数参数是 val ⇒ 编译报 "Val cannot be reassigned"（Kotlin 简单名优先绑局部变量，
        // 再考虑隐式接收者）。别把 `this.` 删掉。
        val msg = MqttMessage(payload).apply { this.qos = 1 }
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
            when (val type = node["type"]?.toString()) {
                "OPEN_DOOR" -> handleOpenDoor(node)
                // 🔴 V308：运维指令此前**全部静默丢弃、不回 ACK** ⇒ 云端
                //    DeviceCommandTracker 要等满 15s ACK_TIMEOUT 才置 TIMEOUT，
                //    运维看到的是「下发成功 → 15 秒后失败」，且设备其实从没收到过。
                //    现在每条都明确回 ACK（哪怕是「未装配执行器」这种失败），
                //    让云端**立刻**拿到真实结论。
                "LOCK", "UNLOCK", "REBOOT", "SET_TARGET_TEMP", "SELF_TEST" -> handleOpsCommand(node, type!!)
                else -> Log.w(TAG, "unknown command type=$type, ignored (no ACK: 不是云端下发格式)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handle message failed", e)
            DeviceStatusHub.setError("MQTT 消息处理失败")
        }
    }

    private fun handleOpenDoor(node: Map<String, Any>) {
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

    /**
     * V308：运维指令统一处理 —— **先判过期，再派发，必回 ACK**。
     *
     * <p>三条约定（改这里前先看）：
     * <ol>
     *   <li><b>过期不执行但仍回失败 ACK</b>（与 OPEN_DOOR 的 H60 处置一致）——
     *       云端 `expireAt` 是 60s 窗口，设备收到时可能已过期。</li>
     *   <li><b>派发异常也要回 ACK</b>：执行器抛异常时若不回，云端又得等 15 秒。
     *       这里捕获后回 `success=false` + 异常摘要。</li>
     *   <li><b>不吞异常语义</b>：ACK 的 message 带原因，让运维一眼看出是
     *       「未装配」还是「执行失败」还是「已过期」。</li>
     * </ol>
     */
    private fun handleOpsCommand(node: Map<String, Any>, type: String) {
        val commandId = node["commandId"] as? String
        if (commandId == null) {
            Log.w(TAG, "ops command without commandId, type=$type, ignored")
            return
        }
        val ops = OpsCommand(
            commandId = commandId,
            type = type,
            expireAt = (node["expireAt"] as? Number)?.toLong(),
            targetTempC = (node["targetTempC"] as? Number)?.toInt()
        )
        if (ops.isExpired()) {
            Log.w(TAG, "ops command expired type=$type commandId=$commandId")
            publishAck(commandId, false, "command expired")
            return
        }
        try {
            onOpsCommand(ops)
        } catch (e: Exception) {
            Log.e(TAG, "ops command failed type=$type commandId=$commandId", e)
            publishAck(commandId, false, "执行异常: ${e.javaClass.simpleName}")
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
