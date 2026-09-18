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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val minioUploader = MinioUploader(appContext)
    private val offlineQueue = OfflineUploadQueue(appContext, minioUploader)
    private lateinit var mqtt: MqttDeviceClient
    /** 近期已处理的开门 commandId，防 MQTT 重投重复开锁 */
    private val recentCommandIds: MutableSet<String> =
        Collections.synchronizedSet(LinkedHashSet())
    /** H70: 会话互斥锁，串行化触碰录像器/会话状态的流程，防并发开门互相覆盖录制文件 */
    private val sessionMutex = Mutex()

    fun start() {
        if (!useMockDriver) {
            (lockDriver as ChzhLockDriver).initialize().onFailure {
                Log.e(TAG, "serial init failed", it)
                DeviceStatusHub.setError("串口初始化失败: ${it.message}")
            }
        }
        OtaChecker.checkOnStartup(appContext)
        mqtt = MqttDeviceClient(
            context = appContext,
            onOpenDoor = { cmd -> handleOpenDoor(cmd) }
        )
        // C22/H62a: 注入事件外发回调（离线上传成功补发关门事件 / 队列放弃告警），
        // MQTT 未连接时经 MqttDeviceClient 内部 OutboundMqttQueue 持久化补投。
        offlineQueue.closedEventPublisher = { sessionId, uploadStatus ->
            mqtt.publishDoorEvent(sessionId, DoorState.CLOSED.name, uploadStatus = uploadStatus)
        }
        offlineQueue.alertPublisher = { alertType, message ->
            mqtt.publishAlert(alertType, message)
        }
        offlineQueue.start()
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
        // H70: 整个会话流程持 sessionMutex 串行；其中 C19c 门磁确认轮询上限 2s、
        // waitUntilClosed 有关门超时，均为有界等待，不会无限期占锁。
        sessionMutex.withLock {
            if (!rememberCommand(cmd.commandId)) {
                Log.w(TAG, "duplicate OPEN_DOOR ignored commandId=${cmd.commandId}")
                return
            }
            Log.i(TAG, "OPEN_DOOR session=${cmd.sessionId} operator=${cmd.operatorMode}")
            DeviceStatusHub.setDoorState(DoorState.OPENING, cmd.sessionId, "收到开门指令")

            var recordingStarted = false
            if (!cmd.operatorMode) {
                videoRecorder.start(cmd.sessionId)
                recordingStarted = true
            }

            lockDriver.unlock().onFailure {
                Log.e(TAG, "unlock failed", it)
                if (recordingStarted) {
                    videoRecorder.stop() // H59: 解锁失败也要停录，避免录像无人关门仍持续进行
                }
                mqtt.publishAck(cmd.commandId, false)
                DeviceStatusHub.setError("开锁失败: ${it.message}")
                return
            }

            // C19c: 开锁指令送达 ≠ 门已打开，轮询门磁反馈（ChzhLockDriver 读线程
            // parseDoorFeedback 更新状态 / Mock 驱动模拟反馈）确认门开后再发 OPEN。
            if (!awaitDoorOpened()) {
                Log.e(TAG, "door open not confirmed session=${cmd.sessionId}")
                if (recordingStarted) {
                    videoRecorder.stop()
                }
                mqtt.publishAck(cmd.commandId, false, "door open not confirmed")
                mqtt.publishDoorEvent(cmd.sessionId, DoorState.CLOSED.name)
                DeviceStatusHub.setError("开锁后门磁未确认打开")
                return
            }

            // 仅在确认门开后 ACK，避免先 success 再 failure 双 ACK
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
                if (recordingStarted) {
                    videoRecorder.stop() // C19a: 超时关门也要停录，录像已落盘待上传
                }
                // C19a: 录像已停止落盘、尚未上传，uploadStatus 用 UPLOADING（trade 侧按待上传处理，不立即结算）
                mqtt.publishDoorEvent(cmd.sessionId, DoorState.CLOSED.name, uploadStatus = "UPLOADING")
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
    }

    /**
     * C19c: 轮询门磁反馈确认门已打开（复用驱动的串口反馈状态），默认上限 2s、200ms 间隔。
     * 带超时，保证不会长时间阻塞会话互斥锁。
     */
    private suspend fun awaitDoorOpened(timeoutMs: Long = 2_000L, pollMs: Long = 200L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (lockDriver.currentDoorState() == DoorState.OPEN) {
                return true
            }
            delay(pollMs)
        }
        return lockDriver.currentDoorState() == DoorState.OPEN
    }

    private fun finishShoppingClose(recording: RecordingResult, userId: Long) {
        val sessionId = recording.sessionId
        val deviceId = EdgeRuntimeConfig.ensureDeviceId(appContext)
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
