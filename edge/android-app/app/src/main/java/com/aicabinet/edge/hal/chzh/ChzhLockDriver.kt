package com.aicabinet.edge.hal.chzh

import android.util.Log
import com.aicabinet.edge.hal.DoorState
import com.aicabinet.edge.hal.ILockDriver
import com.aicabinet.edge.hal.serial.ChzhSerialPort
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 创智辉 M8 串口门锁驱动。
 * 协议：开锁 `L1@200\r\n`，波特率 19200。
 * 门磁：串口回包含 DOOR=C / DOOR=O 时更新状态（可按现场协议扩展）。
 */
class ChzhLockDriver(
    private val serialPath: String = "/dev/ttyS2"
) : ILockDriver {

    private val stateLock = Any()
    private var state = DoorState.CLOSED
    private var serial: ChzhSerialPort? = null
    private val readerRunning = AtomicBoolean(false)
    private var readerThread: Thread? = null

    fun initialize(): Result<Unit> = runCatching {
        shutdown()
        val port = ChzhSerialPort(serialPath)
        port.open().getOrThrow()
        serial = port
        startReader()
        Log.i(TAG, "ChzhLockDriver initialized path=$serialPath")
    }

    fun shutdown() {
        readerRunning.set(false)
        readerThread?.interrupt()
        readerThread = null
        serial?.close()
        serial = null
    }

    override suspend fun unlock(): Result<Unit> = runCatching {
        synchronized(stateLock) { state = DoorState.OPENING }
        sendCommand(UNLOCK_CMD)
        // C19c: 不在此乐观置 OPEN，门磁状态以串口读线程 parseDoorFeedback 收到的反馈为准，
        // CabinetController 轮询 currentDoorState()==OPEN 确认门开后才对外发布 OPEN。
        Log.i(TAG, "unlock sent, awaiting door sensor feedback")
    }

    override suspend fun lock(): Result<Unit> = runCatching {
        synchronized(stateLock) { state = DoorState.CLOSED }
        Log.i(TAG, "door locked (logical)")
    }

    override fun currentDoorState(): DoorState = synchronized(stateLock) { state }

    private fun sendCommand(cmd: ByteArray) {
        // H57: 串口未打开时直接抛出，让 unlock()/上层拿到 failure 而不是静默跳过后误报成功
        val port = serial ?: throw IllegalStateException("serial not open: $serialPath")
        port.write(cmd)
    }

    private fun startReader() {
        if (!readerRunning.compareAndSet(false, true)) return
        readerThread = Thread({
            val buffer = ByteArray(256)
            while (readerRunning.get() && !Thread.interrupted()) {
                try {
                    val n = serial?.read(buffer) ?: -1
                    if (n <= 0) {
                        Thread.sleep(100)
                        continue
                    }
                    val text = String(buffer, 0, n)
                    parseDoorFeedback(text)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "serial read error: ${e.message}")
                }
            }
        }, "chzh-serial-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun parseDoorFeedback(text: String) {
        val upper = text.uppercase()
        synchronized(stateLock) {
            when {
                upper.contains("DOOR=C") || upper.contains("DOOR=0") || upper.contains("CLOSED") ->
                    state = DoorState.CLOSED
                upper.contains("DOOR=O") || upper.contains("DOOR=1") || upper.contains("OPEN") ->
                    state = DoorState.OPEN
            }
        }
    }

    companion object {
        private const val TAG = "ChzhLockDriver"
        val UNLOCK_CMD: ByteArray = "L1@200\r\n".toByteArray()
    }
}
