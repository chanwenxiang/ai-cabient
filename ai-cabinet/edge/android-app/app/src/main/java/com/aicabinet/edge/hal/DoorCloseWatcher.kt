package com.aicabinet.edge.hal

import android.util.Log
import kotlinx.coroutines.delay

object DoorCloseWatcher {
    private const val TAG = "DoorCloseWatcher"

    /** 轮询门锁状态直到 CLOSED 或超时。 */
    suspend fun waitUntilClosed(
        lockDriver: ILockDriver,
        timeoutMs: Long,
        pollMs: Long = 250L
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (lockDriver.currentDoorState() == DoorState.CLOSED) {
                Log.i(TAG, "door closed")
                return true
            }
            delay(pollMs)
        }
        val closed = lockDriver.currentDoorState() == DoorState.CLOSED
        if (!closed) {
            Log.w(TAG, "door close timeout after ${timeoutMs}ms, state=${lockDriver.currentDoorState()}")
        }
        return closed
    }
}
