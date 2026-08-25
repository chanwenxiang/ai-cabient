package com.aicabinet.trade.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsAlertDispatcherTest {

    @Mock private SystemConfigService systemConfigService;

    private OpsAlertDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = spy(new OpsAlertDispatcher(systemConfigService, RestClient.builder()));
        doNothing().when(dispatcher).postJson(anyString(), any());
    }

    @Test
    void send_shouldPostDingTalkPayloadToDingTalkChannel() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_DINGTALK_WEBHOOK, ""))
                .thenReturn("https://oapi.dingtalk.com/robot/send?access_token=abc");

        dispatcher.send("DISPUTE_SLA_REMINDER", "标题", "正文", null);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(dispatcher).postJson(eq("https://oapi.dingtalk.com/robot/send?access_token=abc"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) payload.getValue();
        assertEquals("text", body.get("msgtype"));
        @SuppressWarnings("unchecked")
        Map<String, Object> text = (Map<String, Object>) body.get("text");
        assertEquals("标题\n正文", text.get("content"));
    }

    @Test
    void send_shouldPostWeComPayloadToWeComChannel() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_WECOM_WEBHOOK, ""))
                .thenReturn("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc");

        dispatcher.send("DEVICE_OFFLINE", "设备离线", "CAB-001 已离线", Map.of("deviceId", "CAB-001"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(dispatcher).postJson(eq("https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=abc"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) payload.getValue();
        assertEquals("text", body.get("msgtype"));
    }

    @Test
    void send_shouldPostGenericPayloadToGenericWebhook() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_WEBHOOK, ""))
                .thenReturn("https://ops.example.com/alert");

        dispatcher.send("DISPUTE_SLA_OVERDUE", "超时", "工单 1 超时",
                Map.of("ticketId", 1L, "sessionId", "S1"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(dispatcher).postJson(eq("https://ops.example.com/alert"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) payload.getValue();
        assertEquals("DISPUTE_SLA_OVERDUE", body.get("type"));
        assertEquals("超时", body.get("title"));
        assertEquals(1L, body.get("ticketId"));
        assertEquals("S1", body.get("sessionId"));
    }

    @Test
    void send_shouldAlsoPostToLegacyExtraUrl() {
        dispatcher.send("DISPUTE_SLA_REMINDER", "提醒", "工单 2 即将到期",
                Map.of("ticketId", 2L), "https://legacy.example.com/sla");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(dispatcher).postJson(eq("https://legacy.example.com/sla"), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) payload.getValue();
        assertEquals("DISPUTE_SLA_REMINDER", body.get("type"));
    }

    @Test
    void send_shouldSkipWhenNoChannelConfigured() {
        dispatcher.send("TEST", "标题", "正文", null);

        verify(dispatcher, times(0)).postJson(anyString(), any());
    }

    @Test
    void payloads_shouldMatchRobotMessageContract() {
        Map<String, Object> body = OpsAlertDispatcher.dingTalkPayload("test");
        assertEquals("text", body.get("msgtype"));
        @SuppressWarnings("unchecked")
        Map<String, Object> text = (Map<String, Object>) body.get("text");
        assertEquals("test", text.get("content"));
        assertEquals(body, OpsAlertDispatcher.weComPayload("test"));
        Map<String, Object> generic = OpsAlertDispatcher.genericPayload("T", "标题", "正文", Map.of("k", "v"));
        assertEquals("T", generic.get("type"));
        assertEquals("v", generic.get("k"));
        assertTrue(generic.containsKey("message"));
        assertFalse(generic.containsKey("msgtype"));
    }

}
