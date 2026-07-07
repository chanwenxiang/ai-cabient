package com.aicabinet.device.service;

import com.aicabinet.device.mqtt.MqttCommandPublisher;
import org.springframework.stereotype.Service;

@Service
public class DeviceCommandService {

    private final MqttCommandPublisher mqttPublisher;

    public DeviceCommandService(MqttCommandPublisher mqttPublisher) {
        this.mqttPublisher = mqttPublisher;
    }

    public void openDoor(String deviceId, String sessionId, Long userId, boolean operatorMode) {
        mqttPublisher.publishOpenDoor(deviceId, sessionId, userId, operatorMode);
    }
}
