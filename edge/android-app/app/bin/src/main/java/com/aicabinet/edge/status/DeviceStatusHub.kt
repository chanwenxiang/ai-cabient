package com.aicabinet.edge.status

import com.aicabinet.edge.hal.DoorState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DeviceStatus(
    val mqttConnected: Boolean = false,
    val doorState: DoorState = DoorState.UNKNOWN,
    val activeSessionId: String? = null,
    val lastEvent: String = "启动中",
    val lastError: String? = null
)

/** 供 MainActivity 展示的运行时状态。 */
object DeviceStatusHub {
    private val _status = MutableStateFlow(DeviceStatus())
    val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    fun update(transform: (DeviceStatus) -> DeviceStatus) {
        _status.value = transform(_status.value)
    }

    fun setMqttConnected(connected: Boolean) {
        update { it.copy(mqttConnected = connected, lastEvent = if (connected) "MQTT 已连接" else "MQTT 断开") }
    }

    fun setDoorState(state: DoorState, sessionId: String? = null, event: String? = null) {
        update {
            it.copy(
                doorState = state,
                activeSessionId = sessionId ?: it.activeSessionId,
                lastEvent = event ?: "门状态 $state"
            )
        }
    }

    fun clearSession(event: String) {
        update { it.copy(activeSessionId = null, lastEvent = event) }
    }

    fun setError(message: String) {
        update { it.copy(lastError = message, lastEvent = message) }
    }
}
