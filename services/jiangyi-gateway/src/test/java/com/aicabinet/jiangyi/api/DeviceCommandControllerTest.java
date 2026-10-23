package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.ws.DeviceWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 音量下发：固件 sendDeviceSoundVolume 走 AudioManager.setStreamVolume，会解除静音；
 * 「关」必须只发开关帧，否则随后音量帧把声音放回来。
 */
class DeviceCommandControllerTest {

    private static final String DEVICE_ID = "EG943322621391";
    private static final String IDENTIFIER = "CQYB10720";

    private DeviceWebSocketHandler handler;
    private DeviceCommandController controller;

    @BeforeEach
    void setUp() {
        TradeInternalClient tradeInternalClient = mock(TradeInternalClient.class);
        handler = mock(DeviceWebSocketHandler.class);
        controller = new DeviceCommandController(tradeInternalClient, handler);
        when(tradeInternalClient.device(DEVICE_ID)).thenReturn(Optional.of(
                new TradeInternalClient.JiangyiDeviceView(DEVICE_ID, "SN-1", IDENTIFIER,
                        null, null, "BOUND", 0L, null, null, true)));
        when(handler.isOnline(IDENTIFIER)).thenReturn(true);
        when(handler.send(eq(IDENTIFIER), any())).thenReturn(true);
    }

    @Test
    void sound_whenMuted_sendsOnlySwitchFrame() {
        controller.sound(DEVICE_ID, new DeviceCommandController.SoundRequest(false, 1));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(handler, times(1)).send(eq(IDENTIFIER), captor.capture());
        assertEquals("sendDeviceSoundSwitch", captor.getValue().get("msgType"));
        assertEquals("0", captor.getValue().get("msgContent"));
    }

    @Test
    void sound_whenEnabled_sendsSwitchThenVolume() {
        controller.sound(DEVICE_ID, new DeviceCommandController.SoundRequest(true, 1));

        InOrder inOrder = inOrder(handler);
        inOrder.verify(handler).send(eq(IDENTIFIER), argThat(m ->
                "sendDeviceSoundSwitch".equals(m.get("msgType")) && "1".equals(m.get("msgContent"))));
        inOrder.verify(handler).send(eq(IDENTIFIER), argThat(m ->
                "sendDeviceSoundVolume".equals(m.get("msgType")) && "1".equals(m.get("msgContent"))));
    }

    /** 拉取视频帧（V16 §4.3.2.11）：msgContent=我方 sessionId 原样，不带厂商云的 _MSG 标注。 */
    @Test
    void pullVideo_sendsSessionIdVerbatim() {
        controller.pullVideo(DEVICE_ID, new DeviceCommandController.PullVideoRequest(" 1791884161406172652398 "));

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(handler).send(eq(IDENTIFIER), captor.capture());
        assertEquals("sendUploadErrorOrderVideos", captor.getValue().get("msgType"));
        assertEquals("1791884161406172652398", captor.getValue().get("msgContent"));
        assertEquals(200, captor.getValue().get("status"));
    }

    @Test
    void pullVideo_rejectsBlankOrIllegalOrderNo() {
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.pullVideo(DEVICE_ID, new DeviceCommandController.PullVideoRequest("  ")));
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.pullVideo(DEVICE_ID, new DeviceCommandController.PullVideoRequest("1234/../x")));
        verify(handler, times(0)).send(eq(IDENTIFIER), any());
    }

    /** 关门/回锁（V16 §4.3.2.6 可选指令）：msgContent 空串原样下发。 */
    @Test
    void closeDoor_sendsEmptyContentFrame() {
        controller.closeDoor(DEVICE_ID);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(handler, times(1)).send(eq(IDENTIFIER), captor.capture());
        assertEquals("closeDoor", captor.getValue().get("msgType"));
        assertEquals("", captor.getValue().get("msgContent"));
        assertEquals(200, captor.getValue().get("status"));
    }

    @Test
    void closeDoor_whenSendFails_throws503() {
        when(handler.send(eq(IDENTIFIER), any())).thenReturn(false);
        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> controller.closeDoor(DEVICE_ID));
    }
}
