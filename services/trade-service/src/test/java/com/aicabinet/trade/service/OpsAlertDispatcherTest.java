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

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsAlertDispatcherTest {

    /** 同时带 code 与 errcode=0，使飞书 / 钉钉两类返回体都被判为「已接受」。 */
    private static final String ACCEPTED_BODY = "{\"code\":0,\"errcode\":0,\"msg\":\"success\"}";

    @Mock private SystemConfigService systemConfigService;

    private OpsAlertDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = spy(new OpsAlertDispatcher(systemConfigService, RestClient.builder()));
        doReturn(ACCEPTED_BODY).when(dispatcher).postJson(anyString(), any());
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

    // ---------- 飞书渠道 ----------

    @Test
    void send_shouldPostFeishuPayloadToFeishuChannel() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK, ""))
                .thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/abc");

        dispatcher.send("DEVICE_OFFLINE", "设备离线", "CAB-001 已离线");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(dispatcher).postJson(eq("https://open.feishu.cn/open-apis/bot/v2/hook/abc"),
                payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) payload.getValue();
        assertEquals("text", body.get("msg_type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> content = (Map<String, Object>) body.get("content");
        assertEquals("设备离线\nCAB-001 已离线", content.get("text"));
    }

    /** 飞书用下划线 msg_type；照搬钉钉的 msgtype 会被飞书以 9499 Bad Request 拒收。 */
    @Test
    void feishuPayload_shouldUseUnderscoreMsgTypeAndNotDingTalkShape() {
        Map<String, Object> body = OpsAlertDispatcher.feishuPayload("测试", null);

        assertTrue(body.containsKey("msg_type"));
        assertFalse(body.containsKey("msgtype"));
        assertFalse(body.containsKey("text"));  // 钉钉是顶层 text 节点，飞书是 msg_type+content
        assertEquals("text", body.get("msg_type"));
        assertFalse(body.containsKey("timestamp"));
        assertFalse(body.containsKey("sign"));
    }

    /** 独立预言机：飞书官方文档样例（secret=demo, timestamp=1599360473）的签名值。 */
    @Test
    void feishuSign_shouldMatchOfficialSampleVector() {
        assertEquals("l1N0gAcBjdwBvGm1xMjOF0XSyaLRpR7tuO5dHfhAYc8=",
                OpsAlertDispatcher.feishuSign("demo", 1599360473L));
    }

    @Test
    void feishuPayload_shouldAttachSignWhenSecretConfigured() {
        Map<String, Object> body = OpsAlertDispatcher.feishuPayload("测试", "s3cr3t");

        String timestamp = (String) body.get("timestamp");
        assertNotNull(timestamp);
        assertEquals(OpsAlertDispatcher.feishuSign("s3cr3t", Long.parseLong(timestamp)),
                body.get("sign"));
        assertEquals("text", body.get("msg_type"));
    }

    // ---------- 返回体业务码判定 ----------

    @Test
    void trySend_shouldReturnFalseWhenFeishuRejectsWithBusinessCode() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK, ""))
                .thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/abc");
        doReturn("{\"code\":19024,\"msg\":\"Key Words Not Found\"}")
                .when(dispatcher).postJson(anyString(), any());

        assertFalse(dispatcher.trySend("DEVICE_OFFLINE", "标题", "正文", Map.of()));
    }

    @Test
    void trySend_shouldReturnTrueWhenFeishuAccepts() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK, ""))
                .thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/abc");

        assertTrue(dispatcher.trySend("DEVICE_OFFLINE", "标题", "正文", Map.of()));
    }

    @Test
    void trySend_shouldReturnFalseWhenDingTalkRejectsWithErrcode() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_DINGTALK_WEBHOOK, ""))
                .thenReturn("https://oapi.dingtalk.com/robot/send?access_token=abc");
        doReturn("{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}")
                .when(dispatcher).postJson(anyString(), any());

        assertFalse(dispatcher.trySend("DEVICE_OFFLINE", "标题", "正文", Map.of()));
    }

    /** 反向守卫：无法核验的返回体不得被误判为失败（假红与假绿同害）。 */
    @Test
    void deliveryError_shouldNotFlagUnverifiableBodies() {
        assertNull(OpsAlertDispatcher.deliveryError("FEISHU", null));
        assertNull(OpsAlertDispatcher.deliveryError("FEISHU", ""));
        assertNull(OpsAlertDispatcher.deliveryError("FEISHU", "   "));
        assertNull(OpsAlertDispatcher.deliveryError("FEISHU", "OK"));
        assertNull(OpsAlertDispatcher.deliveryError("FEISHU", "{\"foo\":1}"));
        assertNull(OpsAlertDispatcher.deliveryError("FEISHU", "{}"));
        assertNull(OpsAlertDispatcher.deliveryError("FEISHU", "{\"code\":\"19024\"}"));
        assertNull(OpsAlertDispatcher.deliveryError("FEISHU", "{\"StatusCode\":0,\"code\":0}"));
        assertNull(OpsAlertDispatcher.deliveryError("DINGTALK", "{\"code\":19024}"));
    }

    /** 通用 Webhook 无返回体契约，即使带 errcode 也不得判失败。 */
    @Test
    void deliveryError_shouldNotInspectGenericWebhookBody() {
        assertNull(OpsAlertDispatcher.deliveryError("WEBHOOK", "{\"errcode\":123}"));
    }

    @Test
    void deliveryError_shouldReportPositiveRejectionWithDetail() {
        assertEquals("code=19024 Key Words Not Found",
                OpsAlertDispatcher.deliveryError("FEISHU",
                        "{\"code\":19024,\"msg\":\"Key Words Not Found\"}"));
        assertEquals("errcode=310000 keywords not in content",
                OpsAlertDispatcher.deliveryError("WECOM",
                        "{\"errcode\":310000,\"errmsg\":\"keywords not in content\"}"));
    }

    /** send() 维持「不影响主流程」语义：渠道被拒也不抛异常。 */
    @Test
    void send_shouldNotThrowWhenChannelRejects() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK, ""))
                .thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/abc");
        doReturn("{\"code\":9499,\"msg\":\"Bad Request\"}")
                .when(dispatcher).postJson(anyString(), any());

        dispatcher.send("DEVICE_OFFLINE", "标题", "正文");
    }

    // ---------- 运营台「测试发送」 ----------

    @Test
    void probeChannels_shouldReportRejectionWithPlatformDetail() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK, ""))
                .thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/abc");
        doReturn("{\"code\":19024,\"msg\":\"Key Words Not Found\"}")
                .when(dispatcher).postJson(anyString(), any());

        List<OpsAlertDispatcher.ChannelProbe> probes =
                dispatcher.probeChannels("ALERT_CHANNEL_TEST", "标题", "正文");

        assertEquals(1, probes.size());  // 只有飞书配了 URL，其余渠道不出现
        assertEquals("FEISHU", probes.get(0).channel());
        assertFalse(probes.get(0).delivered());
        assertEquals("code=19024 Key Words Not Found", probes.get(0).detail());
    }

    @Test
    void probeChannels_shouldReportDeliveredOnSuccess() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK, ""))
                .thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/abc");

        List<OpsAlertDispatcher.ChannelProbe> probes =
                dispatcher.probeChannels("ALERT_CHANNEL_TEST", "标题", "正文");

        assertEquals(1, probes.size());
        assertTrue(probes.get(0).delivered());
        assertNull(probes.get(0).detail());
    }

    @Test
    void probeChannels_shouldSkipUnconfiguredChannels() {
        assertTrue(dispatcher.probeChannels("ALERT_CHANNEL_TEST", "标题", "正文").isEmpty());
    }

    /** 试发遇到异常也要返回值：否则页面只看到 500，拿不到「是哪个渠道、为什么」。 */
    @Test
    void probeChannels_shouldReportFailureInsteadOfThrowing() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK, ""))
                .thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/abc");
        doThrow(new IllegalStateException("connection refused"))
                .when(dispatcher).postJson(anyString(), any());

        List<OpsAlertDispatcher.ChannelProbe> probes =
                dispatcher.probeChannels("ALERT_CHANNEL_TEST", "标题", "正文");

        assertEquals(1, probes.size());
        assertFalse(probes.get(0).delivered());
        assertEquals("connection refused", probes.get(0).detail());
    }

}
