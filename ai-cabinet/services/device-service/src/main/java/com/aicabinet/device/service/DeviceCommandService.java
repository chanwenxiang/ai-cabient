package com.aicabinet.device.service;

import com.aicabinet.device.mqtt.MqttCommandPublisher;
import org.springframework.stereotype.Service;

@Service
public class DeviceCommandService {

    private final MqttCommandPublisher mqttPublisher;
    private final DeviceCommandTracker commandTracker;

    public DeviceCommandService(MqttCommandPublisher mqttPublisher,
                                DeviceCommandTracker commandTracker) {
        this.mqttPublisher = mqttPublisher;
        this.commandTracker = commandTracker;
    }

    public String openDoor(String deviceId, String sessionId, Long userId, boolean operatorMode) {
        String commandId = mqttPublisher.publishOpenDoor(deviceId, sessionId, userId, operatorMode);
        commandTracker.recordPublished(commandId, deviceId, sessionId);
        return commandId;
    }
}
