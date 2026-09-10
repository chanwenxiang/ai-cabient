package com.aicabinet.edge.service

import android.content.Context
import android.util.Log
import com.aicabinet.edge.config.EdgeRuntimeConfig
import com.aicabinet.edge.hal.DoorCloseWatcher
import com.aicabinet.edge.hal.DoorState
import com.aicabinet.edge.hal.ILockDriver
import com.aicabinet.edge.hal.chzh.ChzhLockDriver
import com.aicabinet.edge.hal.mock.MockLockDriver
import com.aicabinet.edge.mqtt.MqttDeviceClient
import com.aicabinet.edge.ota.OtaChecker
import com.aicabinet.edge.status.DeviceStatusHub
import com.aicabinet.edge.upload.OfflineUploadQueue
import com.aicabinet.edge.upload.MinioUploader
import com.aicabinet.edge.video.RecordingResult
import com.aicabinet.edge.video.SessionVideoRecorder
import com.aicabinet.edge.video.VideoClipJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.LinkedHashSet

class CabinetController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val videoRecorder: SessionVideoRecorder
) {
    private val appContext = context.applicationContext
    private val useMockDriver = EdgeRuntimeConfig.useMockDriver(appContext)
    private val lockDriver: ILockDriver = if (useMockDriver) {
        MockLockDriver(scope, EdgeRuntimeConfig.mockShoppingMs(appContext))
    } else {
        ChzhLockDriver(EdgeRuntimeConfig.serialPortPath(appContext))
    }
    private val minioUploader = MinioUploader()
    private val offlineQueue = OfflineUploadQueue(appContext, minioUploader)
    private lateinit var mqtt: MqttDeviceClient
    /** 近期已处理的开门 commandId，防 MQTT 重投重复开锁 */
    private val recentCommandIds: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet())

    fun start() {
        if (!useMockDriver) {
            (lockDriver as ChzhLockDriver).initialize().onFailure {
                Log.e(TAG, "serial init failed", it)
                DeviceStatusHub.setError("串口初始化失败: ${it.message}")
            }
        }
        OtaChecker.checkOnStartup(appContext)
        offlineQueue.start()
        mqtt = MqttDeviceClient(
            context = appContext,
            onOpenDoor = { cmd -> handleOpenDoor(cmd) }
        )
        mqtt.connect()
        DeviceStatusHub.setDoorState(lockDriver.currentDoorState(), event = "服务已启动")
    }

    fun stop() {
        offlineQueue.stop()
        if (::mqtt.isInitialized) {
            mqtt.disconnect()
        }
        if (lockDriver is ChzhLockDriver) {
            lockDriver.shutdown()
        }
    }

    fun simulateDoorCloseForMock() {
        (lockDriver as? MockLockDriver)?.simulateUserClose()
        DeviceStatusHub.setDoorState(DoorState.CLOSED, event = "手动模拟关门")
    }

    fun mockDriverEnabled(): Boolean = useMockDriver

    private fun rememberCommand(commandId: String): Boolean {
        if (commandId.isBlank()) return true
        synchronized(recentCommandIds) {
            if (!recentCommandIds.add(commandId)) return false
            while (recentCommandIds.size > MAX_RECENT_COMMANDS) {
                val iterator = recentCommandIds.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
            return true
        }
    }

    private fun handleOpenDoor(cmd: MqttDeviceClient.OpenDoorCommand) {
        scope.launch {
            handleOpenDoorInternal(cmd)
        }
    }

    private suspend fun handleOpenDoorInternal(cmd: MqttDeviceClient.OpenDoorCommand) {
        if (!rememberCommand(cmd.commandId)) {
            Log.w(TAG, "duplicate OPEN_DOOR ignored commandId=${cmd.commandId}")
            return
        }
        Log.i(TAG, "OPEN_DOOR session=${cmd.sessionId} operator=${cmd.operatorMode}")
        DeviceStatusHub.setDoorState(DoorState.OPENING, cmd.sessionId, "收到开门指令")

        if (!cmd.operatorMode) {
            videoRecorder.start(cmd.sessionId)
        }

        lockDriver.unlock().onFailure {
            Log.e(TAG, "unlock failed", it)
            mqtt.publishAck(cmd.commandId, false)
            DeviceStatusHub.setError("开锁失败: ${it.message}")
            return
        }
        // 仅在开锁成功后 ACK，避免先 success 再 failure 双 ACK
        mqtt.publishAck(cmd.commandId, true)
        mqtt.publishDoorEvent(cmd.sessionId, DoorState.OPEN.name)
        DeviceStatusHub.setDoorState(DoorState.OPEN, cmd.sessionId, "门已开")

        val timeoutMs = if (cmd.operatorMode) {
            EdgeRuntimeConfig.operatorCloseTimeoutMs(appContext)
        } else {
            EdgeRuntimeConfig.shoppingCloseTimeoutMs(appContext)
        }
        val closed = DoorCloseWatcher.waitUntilClosed(lockDriver, timeoutMs)
        if (!closed) {
            DeviceStatusHub.setError("等待关门超时")
            mqtt.publishDoorEvent(cmd.sessionId, DoorState.CLOSED.name, uploadStatus = "TIMEOUT")
            DeviceStatusHub.clearSession("关门超时")
            return
        }

        if (cmd.operatorMode) {
            lockDriver.lock()
            mqtt.publishDoorEvent(cmd.sessionId, DoorState.CLOSED.name)
            DeviceStatusHub.setDoorState(DoorState.CLOSED, event = "补货关门完成")
            DeviceStatusHub.clearSession("补货会话结束")
            return
        }

        val recording = videoRecorder.stop()
        lockDriver.lock()
        finishShoppingClose(recording, cmd.userId)
        DeviceStatusHub.setDoorState(DoorState.CLOSED, event = "购物关门完成")
        DeviceStatusHub.clearSession("购物会话结束")
    }

    private fun finishShoppingClose(recording: RecordingResult, userId: Long) {
        val sessionId = recording.sessionId
        val deviceId = EdgeRuntimeConfig.deviceId(appContext)
        val files = recording.clips.associate { it.camera to it.file }
        if (files.values.none { it.exists() }) {
            mqtt.publishDoorEvent(sessionId, DoorState.CLOSED.name, uploadStatus = "UPLOADED")
            return
        }
        try {
            val uriPairs = recording.clips.map { clip ->
                clip.camera to minioUploader.uploadVideoStrict(
                    sessionId, clip.file, clip.camera, deviceId, userId
                )
            }
            val primaryUri = uriPairs.first { it.first == "TOP" }.second
            val clipsJson = if (recording.fusionMode == "MULTI") VideoClipJson.build(uriPairs) else null
            mqtt.publishDoorEvent(
                sessionId,
                DoorState.CLOSED.name,
                primaryUri,
                "UPLOADED",
                clipsJson,
                recording.fusionMode
            )
            files.values.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "upload failed, offline queue session=$sessionId", e)
            mqtt.publishDoorEvent(sessionId, DoorState.CLOSED.name, uploadStatus = "LOCAL_QUEUED")
            offlineQueue.enqueue(sessionId, files, recording.fusionMode, deviceId, userId)
        }
    }

    companion object {
        private const val TAG = "CabinetController"
        private const val MAX_RECENT_COMMANDS = 64
    }
}

object CabinetForegroundService {
    private var controller: CabinetController? = null

    fun init(context: Context, scope: CoroutineScope, videoRecorder: SessionVideoRecorder) {
        if (controller == null) {
            controller = CabinetController(context.applicationContext, scope, videoRecorder)
        }
    }

    fun start(context: Context) {
        androidx.core.content.ContextCompat.startForegroundService(
            context,
            android.content.Intent(context, CabinetService::class.java)
        )
    }

    fun getController(context: Context): CabinetController {
        return controller ?: throw IllegalStateException("CabinetService not started")
    }
}
