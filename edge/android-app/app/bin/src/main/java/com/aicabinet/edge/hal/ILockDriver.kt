package com.aicabinet.edge.hal

enum class DoorState { UNKNOWN, OPEN, CLOSED, OPENING }

interface ILockDriver {
    suspend fun unlock(): Result<Unit>
    suspend fun lock(): Result<Unit>
    fun currentDoorState(): DoorState
}
