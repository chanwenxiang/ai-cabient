package com.aicabinet.device.service;

import com.aicabinet.device.client.TradeServiceClient;
import com.aicabinet.device.mqtt.MqttCommandPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DeviceCommandService {

    private final MqttCommandPublisher mqttPublisher;
    private final DeviceCommandTracker commandTracker;
    private final TradeServiceClient tradeServiceClient;

    public DeviceCommandService(MqttCommandPublisher mqttPublisher,
                                DeviceCommandTracker commandTracker,
                                TradeServiceClient tradeServiceClient) {
        this.mqttPublisher = mqttPublisher;
        this.commandTracker = commandTracker;
        this.tradeServiceClient = tradeServiceClient;
    }

    public String openDoor(String deviceId, String sessionId, Long userId, boolean operatorMode) {
        assertDeviceRegistered(deviceId);
        String commandId = mqttPublisher.publishOpenDoor(deviceId, sessionId, userId, operatorMode);
        commandTracker.recordPublished(commandId, deviceId, sessionId);
        return commandId;
    }

    public String setTargetTemp(String deviceId, int targetTempC) {
        assertDeviceRegistered(deviceId);
        String commandId = mqttPublisher.publishSetTargetTemp(deviceId, targetTempC);
        commandTracker.recordPublished(commandId, deviceId, null);
        return commandId;
    }

    public String sendOpsCommand(String deviceId, String commandType) {
        assertDeviceRegistered(deviceId);
        String commandId = mqttPublisher.publishOpsCommand(deviceId, commandType);
        commandTracker.recordPublished(commandId, deviceId, null);
        return commandId;
    }

    public DeviceCommandTracker.CommandStatus getCommandStatus(String commandId) {
        return commandTracker.getStatus(commandId);
    }

    /** B-22：未知柜机不发 MQTT，避免污染 broker / 误操作。 */
    private void assertDeviceRegistered(String deviceId) {
        if (deviceId == null || deviceId.isBlank() || !tradeServiceClient.deviceExists(deviceId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "device not found");
        }
    }
}
