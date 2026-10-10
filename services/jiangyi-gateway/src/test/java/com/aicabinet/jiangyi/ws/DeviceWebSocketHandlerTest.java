package com.aicabinet.jiangyi.ws;

import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WS 通道（CB-022 一期 + CB-023 二期模型面）单测：
 * updateModel 下发报文契约（V16 §4.3.2.9）/ downloadModelNotify 宽容解析（对象/字符串/
 * 缺 modelName 三形态）/ 心跳 PONG / 未知消息忽略。
 */
@ExtendWith(MockitoExtension.class)
class DeviceWebSocketHandlerTest {

    private static final String IDENTIFIER = "CQYB11253";
    private static final String DEVICE_ID = "100000000001";

    @Mock private TradeInternalClient tradeInternalClient;

    private DeviceWebSocketHandler handler;

    @BeforeEach
    void setUp() {
        handler = new DeviceWebSocketHandler(tradeInternalClient);
    }

    /** 最小可用 WS 会话 mock：attributes 带 identifier/deviceId，isOpen=true。 */
    private WebSocketSession onlineSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attrs = new HashMap<>();
        attrs.put(DeviceHandshakeInterceptor.ATTR_IDENTIFIER, IDENTIFIER);
        attrs.put(DeviceHandshakeInterceptor.ATTR_DEVICE_ID, DEVICE_ID);
        when(session.getAttributes()).thenReturn(attrs);
        org.mockito.Mockito.lenient().when(session.isOpen()).thenReturn(true);
        org.mockito.Mockito.lenient().when(session.getId()).thenReturn("s1");
        return session;
    }

    @Test
    void sendModelUpdate_offlineReturnsFalse() {
        assertFalse(handler.sendModelUpdate(IDENTIFIER, "m1", "http://m", "http://t", 2));
    }

    @Test
    void sendModelUpdate_payloadMatchesV16Contract() throws Exception {
        WebSocketSession session = onlineSession();
        handler.afterConnectionEstablished(session);

        assertTrue(handler.sendModelUpdate(IDENTIFIER, "JY-DOOR-01-model",
                "http://oss/model.rknn", "http://oss/classes.txt", 2));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(1000)).sendMessage(captor.capture());
        JsonNode payload = new ObjectMapper().readTree(captor.getValue().getPayload());
        assertEquals("updateModel", payload.path("msgType").asText());
        assertEquals(2, payload.path("msgContent").path("quantity").asInt());
        assertEquals("http://oss/model.rknn", payload.path("msgContent").path("modelUrl").asText());
        assertEquals("http://oss/classes.txt", payload.path("msgContent").path("textUrl").asText());
        assertEquals("JY-DOOR-01-model", payload.path("msgContent").path("modelName").asText());
    }

    @Test
    void downloadModelNotify_objectMsgContentReportsModelConfirmed() {
        WebSocketSession session = onlineSession();
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"msgType\":\"downloadModelNotify\",\"msgContent\":{\"modelName\":\"JY-DOOR-01-model\"}}"));

        verify(tradeInternalClient, timeout(1000)).modelConfirmed(DEVICE_ID, "JY-DOOR-01-model");
    }

    @Test
    void downloadModelNotify_textualMsgContentTolerated() {
        // 真实 msgContent 形态待真机实锤（CB-023 台账局限 2）——字符串形态也接受
        WebSocketSession session = onlineSession();
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"msgType\":\"downloadModelNotify\",\"msgContent\":\"JY-DOOR-01-model\"}"));

        verify(tradeInternalClient, timeout(1000)).modelConfirmed(DEVICE_ID, "JY-DOOR-01-model");
    }

    @Test
    void downloadModelNotify_missingModelNameIsDropped() {
        WebSocketSession session = onlineSession();
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"msgType\":\"downloadModelNotify\",\"msgContent\":{\"foo\":1}}"));

        verify(tradeInternalClient, never()).modelConfirmed(any(), any());
    }

    @Test
    void heartBeatRepliesWithPongEnvelope() throws Exception {
        WebSocketSession session = onlineSession();
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"msgType\":\"heartBeat\",\"msgContent\":\"PONG\"}"));

        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, timeout(1000).atLeastOnce()).sendMessage(captor.capture());
        JsonNode payload = new ObjectMapper().readTree(
                captor.getAllValues().get(captor.getAllValues().size() - 1).getPayload());
        assertEquals(200, payload.path("status").asInt());
        assertEquals("heartBeat", payload.path("msgType").asText());
        assertEquals("PONG", payload.path("msgContent").asText());
    }

    @Test
    void unknownUpstreamIsIgnoredWithoutBusinessCalls() {
        WebSocketSession session = onlineSession();
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage(
                "{\"msgType\":\"someFutureMessage\",\"msgContent\":\"x\"}"));
        handler.handleTextMessage(session, new TextMessage("not-json-at-all"));

        verify(tradeInternalClient, never()).modelConfirmed(any(), any());
    }

    @Test
    void disconnectReportsOffline() {
        WebSocketSession session = onlineSession();
        handler.afterConnectionEstablished(session);
        handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
        verify(tradeInternalClient, timeout(1000)).wsOffline(DEVICE_ID);
    }
}
