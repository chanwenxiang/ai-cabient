package com.aicabinet.edge.hal.mock

import com.aicabinet.edge.hal.DoorState
import com.aicabinet.edge.hal.ILockDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 无硬件时使用，模拟开门并在购物时长后自动关门。 */
class MockLockDriver(
    private val scope: CoroutineScope,
    private val autoCloseAfterMs: Long = 5_000L
) : ILockDriver {

    private var state = DoorState.CLOSED
    private var autoCloseJob: Job? = null

    override suspend fun unlock(): Result<Unit> {
        autoCloseJob?.cancel()
        state = DoorState.OPENING
        delay(300)
        state = DoorState.OPEN
        scheduleAutoClose()
        return Result.success(Unit)
    }

    override suspend fun lock(): Result<Unit> {
        autoCloseJob?.cancel()
        state = DoorState.CLOSED
        return Result.success(Unit)
    }

    override fun currentDoorState(): DoorState = state

    /** 开发 UI 手动模拟用户关门。 */
    fun simulateUserClose() {
        autoCloseJob?.cancel()
        state = DoorState.CLOSED
    }

    private fun scheduleAutoClose() {
        if (autoCloseAfterMs <= 0) return
        autoCloseJob = scope.launch {
            delay(autoCloseAfterMs)
            if (state == DoorState.OPEN) {
                state = DoorState.CLOSED
            }
        }
    }
}
