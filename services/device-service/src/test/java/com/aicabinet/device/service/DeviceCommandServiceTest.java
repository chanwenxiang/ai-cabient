package com.aicabinet.device.service;

import com.aicabinet.device.client.TradeServiceClient;
import com.aicabinet.device.mqtt.MqttCommandPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceCommandServiceTest {

    @Mock MqttCommandPublisher mqttPublisher;
    @Mock DeviceCommandTracker commandTracker;
    @Mock TradeServiceClient tradeServiceClient;

    private DeviceCommandService service;

    @BeforeEach
    void setUp() {
        service = new DeviceCommandService(mqttPublisher, commandTracker, tradeServiceClient);
    }

    /** Q8: 未知柜 OPEN_DOOR → 404，不向 broker 发令。 */
    @Test
    void openDoor_unknownDevice_returns404_withoutMqtt() {
        when(tradeServiceClient.deviceExists("CAB-UNKNOWN")).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.openDoor("CAB-UNKNOWN", "S-1", 7L, false));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(mqttPublisher, never()).publishOpenDoor(anyString(), anyString(), anyLong(), anyBoolean());
        verify(commandTracker, never()).recordPublished(anyString(), anyString(), anyString());
    }

    @Test
    void openDoor_registeredDevice_publishes() {
        when(tradeServiceClient.deviceExists("CAB-001")).thenReturn(true);
        when(mqttPublisher.publishOpenDoor("CAB-001", "S-1", 7L, false)).thenReturn("cmd-1");

        assertEquals("cmd-1", service.openDoor("CAB-001", "S-1", 7L, false));
        verify(commandTracker).recordPublished("cmd-1", "CAB-001", "S-1");
    }
}
