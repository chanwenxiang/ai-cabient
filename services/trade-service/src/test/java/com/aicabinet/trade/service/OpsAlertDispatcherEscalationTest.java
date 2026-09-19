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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0 告警升级链的**结局矩阵**：什么情况下该升级、打给谁、哪一级、什么情况下**必须不升级**。
 *
 * <p>与 {@link OpsAlertDispatcherTest} 分开：那边守的是「聊天渠道怎么发」，这边守的是
 * 「没人收到时怎么办」，两者的失败方向相反（后者猜错收件人的代价更高）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsAlertDispatcherEscalationTest {

    private static final String ACCEPTED_BODY = "{\"code\":0,\"errcode\":0,\"msg\":\"success\"}";
    private static final String SMS_URL = "https://sms-gw.internal/send";
    private static final String PHONE_URL = "https://voice-gw.internal/call";
    private static final String ONCALL_JSON =
            "[{\"name\":\"张三\",\"phone\":\"13800000000\",\"startHour\":9,\"endHour\":18}]";

    /** 周一 10:00 —— 落在上面值班表的 9–18 窗口内。 */
    private static final LocalDateTime MONDAY_10 = LocalDateTime.of(2026, 9, 21, 10, 0);

    @Mock private SystemConfigService systemConfigService;

    private OpsAlertDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = org.mockito.Mockito.spy(
                new OpsAlertDispatcher(systemConfigService, RestClient.builder()));
        doReturn(ACCEPTED_BODY).when(dispatcher).postJson(anyString(), any());
        doReturn(MONDAY_10).when(dispatcher).now();
    }

    /** 打开升级链并给出：白名单类型 + 值班表 + 两级网关。 */
    private void enableEscalation(String types, String roster, String smsUrl, String phoneUrl) {
        when(systemConfigService.getBoolean(SystemConfigService.OPS_ALERT_ESCALATION_ENABLED, false))
                .thenReturn(true);
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_ESCALATION_TYPES, ""))
                .thenReturn(types);
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_ONCALL_ROSTER, ""))
                .thenReturn(roster);
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_ESCALATION_SMS_WEBHOOK, ""))
                .thenReturn(smsUrl);
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_ESCALATION_PHONE_WEBHOOK, ""))
                .thenReturn(phoneUrl);
    }

    // ---------- 不该升级的三种情形 ----------

    @Test
    void disabledByDefault_doesNotEscalate() {
        // 默认关（含「配置行缺失」）⇒ 零行为变化。故意不 stub getBoolean，让它取默认 false。
        OpsAlertDispatcher.EscalationOutcome outcome = dispatcher.escalateIfNeeded(
                "DISPUTE_SLA_OVERDUE", "标题", "正文", false);

        assertEquals(OpsAlertDispatcher.EscalationOutcome.DISABLED, outcome);
        verify(dispatcher, never()).postJson(anyString(), any());
    }

    @Test
    void chatDelivered_doesNotEscalate() {
        enableEscalation("DISPUTE_SLA_OVERDUE", ONCALL_JSON, SMS_URL, PHONE_URL);

        OpsAlertDispatcher.EscalationOutcome outcome = dispatcher.escalateIfNeeded(
                "DISPUTE_SLA_OVERDUE", "标题", "正文", true);

        assertEquals(OpsAlertDispatcher.EscalationOutcome.CHAT_DELIVERED, outcome);
        verify(dispatcher, never()).postJson(anyString(), any());
    }

    @Test
    void typeOutsideWhitelist_doesNotEscalate() {
        enableEscalation("DISPUTE_SLA_OVERDUE", ONCALL_JSON, SMS_URL, PHONE_URL);

        OpsAlertDispatcher.EscalationOutcome outcome = dispatcher.escalateIfNeeded(
                "DEVICE_TEMP_HIGH", "标题", "正文", false);

        assertEquals(OpsAlertDispatcher.EscalationOutcome.NOT_P0, outcome);
        verify(dispatcher, never()).postJson(anyString(), any());
    }

    @Test
    void whitelistMatchIsExact_notPrefix() {
        // 「WECHAT_REFUND_ABNORMAL」在名单里，但带后缀的近亲不在 —— 前缀匹配会把无关告警也升级。
        enableEscalation("WECHAT_REFUND_ABNORMAL", ONCALL_JSON, SMS_URL, PHONE_URL);

        assertEquals(OpsAlertDispatcher.EscalationOutcome.NOT_P0, dispatcher.escalateIfNeeded(
                "WECHAT_REFUND_ABNORMAL_EXTRA", "标题", "正文", false));
        assertEquals(OpsAlertDispatcher.EscalationOutcome.SMS_SENT, dispatcher.escalateIfNeeded(
                "WECHAT_REFUND_ABNORMAL", "标题", "正文", false));
    }

    @Test
    void emptyRoster_failsClosed_andNeverGuessesARecipient() {
        enableEscalation("DISPUTE_SLA_OVERDUE", "", SMS_URL, PHONE_URL);

        OpsAlertDispatcher.EscalationOutcome outcome = dispatcher.escalateIfNeeded(
                "DISPUTE_SLA_OVERDUE", "标题", "正文", false);

        assertEquals(OpsAlertDispatcher.EscalationOutcome.NO_ONCALL, outcome);
        verify(dispatcher, never()).postJson(anyString(), any());
    }

    @Test
    void nobodyOnCallAtThisHour_failsClosed() {
        // 值班表只在 9–18 有人；把「现在」挪到凌晨 2 点 ⇒ 不升级。
        doReturn(LocalDateTime.of(2026, 9, 21, 2, 0)).when(dispatcher).now();
        enableEscalation("DISPUTE_SLA_OVERDUE", ONCALL_JSON, SMS_URL, PHONE_URL);

        OpsAlertDispatcher.EscalationOutcome outcome = dispatcher.escalateIfNeeded(
                "DISPUTE_SLA_OVERDUE", "标题", "正文", false);

        assertEquals(OpsAlertDispatcher.EscalationOutcome.NO_ONCALL, outcome);
        verify(dispatcher, never()).postJson(anyString(), any());
    }

    // ---------- 该升级的路径 ----------

    @Test
    void smsDelivered_escalatesToSmsWithOnCallRecipient() {
        enableEscalation("DISPUTE_SLA_OVERDUE", ONCALL_JSON, SMS_URL, PHONE_URL);

        OpsAlertDispatcher.EscalationOutcome outcome = dispatcher.escalateIfNeeded(
                "DISPUTE_SLA_OVERDUE", "[争议SLA超时]", "工单 1 已超时", false);

        assertEquals(OpsAlertDispatcher.EscalationOutcome.SMS_SENT, outcome);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(dispatcher).postJson(org.mockito.ArgumentMatchers.eq(SMS_URL), payload.capture());
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) payload.getValue();
        assertEquals("DISPUTE_SLA_OVERDUE", body.get("type"));
        assertEquals("[争议SLA超时]", body.get("title"));
        assertEquals("张三", body.get("onCall"));
        assertEquals("13800000000", body.get("phoneNumber"));
        // 短信成功就不该再打电话（否则值班人半夜被短信+电话各吵一次）。
        verify(dispatcher, never()).postJson(org.mockito.ArgumentMatchers.eq(PHONE_URL), any());
    }

    @Test
    void smsNotConfigured_fallsThroughToPhone() {
        enableEscalation("DISPUTE_SLA_OVERDUE", ONCALL_JSON, "", PHONE_URL);

        OpsAlertDispatcher.EscalationOutcome outcome = dispatcher.escalateIfNeeded(
                "DISPUTE_SLA_OVERDUE", "标题", "正文", false);

        assertEquals(OpsAlertDispatcher.EscalationOutcome.PHONE_SENT, outcome);
        verify(dispatcher).postJson(org.mockito.ArgumentMatchers.eq(PHONE_URL), any());
    }

    @Test
    void smsRejectedByBusinessCode_fallsThroughToPhone() {
        // 🔴 本文件最该守的一条：网关回 **HTTP 200** 但业务码非 0（余额不足/号码黑名单），
        // 必须判为「没送达」并升级到电话。只判 HTTP 状态码就会在这里静默放过。
        doAnswer(inv -> inv.getArgument(0).equals(SMS_URL)
                        ? "{\"code\":500,\"msg\":\"余额不足\"}"
                        : ACCEPTED_BODY)
                .when(dispatcher).postJson(anyString(), any());
        enableEscalation("DISPUTE_SLA_OVERDUE", ONCALL_JSON, SMS_URL, PHONE_URL);

        OpsAlertDispatcher.EscalationOutcome outcome = dispatcher.escalateIfNeeded(
                "DISPUTE_SLA_OVERDUE", "标题", "正文", false);

        assertEquals(OpsAlertDispatcher.EscalationOutcome.PHONE_SENT, outcome);
        verify(dispatcher).postJson(org.mockito.ArgumentMatchers.eq(PHONE_URL), any());
    }

    @Test
    void bothLevelsUnconfigured_reportsFailedWithoutPretending() {
        enableEscalation("DISPUTE_SLA_OVERDUE", ONCALL_JSON, "", "");

        OpsAlertDispatcher.EscalationOutcome outcome = dispatcher.escalateIfNeeded(
                "DISPUTE_SLA_OVERDUE", "标题", "正文", false);

        assertEquals(OpsAlertDispatcher.EscalationOutcome.FAILED, outcome);
        verify(dispatcher, never()).postJson(anyString(), any());
    }

    @Test
    void emptyWhitelist_escalatesEveryType() {
        // 留空 = 全类型（fail-loud：把名单清空会让升级更激进，而不是静默失效）。
        enableEscalation("", ONCALL_JSON, SMS_URL, PHONE_URL);

        assertEquals(OpsAlertDispatcher.EscalationOutcome.SMS_SENT, dispatcher.escalateIfNeeded(
                "SOMETHING_UNLISTED", "标题", "正文", false));
    }

    // ---------- 与真实分发路径的接线 ----------

    @Test
    void send_withNoChatChannelDelivered_escalates() {
        // 端到端：聊天渠道一条都没配（等同于「没人收到」）⇒ send() 也必须触发升级。
        enableEscalation("DISPUTE_SLA_OVERDUE", ONCALL_JSON, SMS_URL, PHONE_URL);

        dispatcher.send("DISPUTE_SLA_OVERDUE", "标题", "正文", Map.of());

        verify(dispatcher).postJson(org.mockito.ArgumentMatchers.eq(SMS_URL), any());
    }

    @Test
    void trySend_returnValueStillMeansChatChannels_only() {
        // 返回值语义不变：没配聊天渠道 ⇒ true（「无需投递」），不因为升级链而改变。
        enableEscalation("DISPUTE_SLA_OVERDUE", ONCALL_JSON, SMS_URL, PHONE_URL);

        assertTrue(dispatcher.trySend("DISPUTE_SLA_OVERDUE", "标题", "正文", Map.of()));
    }

    @Test
    void deliveredChatChannel_suppressesEscalationEndToEnd() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK, ""))
                .thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/abc");
        enableEscalation("DISPUTE_SLA_OVERDUE", ONCALL_JSON, SMS_URL, PHONE_URL);

        dispatcher.send("DISPUTE_SLA_OVERDUE", "标题", "正文", Map.of());

        verify(dispatcher).postJson(
                org.mockito.ArgumentMatchers.eq("https://open.feishu.cn/open-apis/bot/v2/hook/abc"),
                any());
        verify(dispatcher, times(0)).postJson(org.mockito.ArgumentMatchers.eq(SMS_URL), any());
    }

    @Test
    void allChatChannelsRejected_escalates() {
        when(systemConfigService.getValue(SystemConfigService.OPS_ALERT_FEISHU_WEBHOOK, ""))
                .thenReturn("https://open.feishu.cn/open-apis/bot/v2/hook/abc");
        doAnswer(inv -> "{\"code\":19024,\"msg\":\"Key Words Not Found\"}")
                .when(dispatcher).postJson(anyString(), any());
        enableEscalation("DISPUTE_SLA_OVERDUE", ONCALL_JSON, SMS_URL, PHONE_URL);

        dispatcher.send("DISPUTE_SLA_OVERDUE", "标题", "正文", Map.of());

        // 飞书被业务码拒收 ⇒ 聊天侧「没人收到」⇒ 必须升级。
        verify(dispatcher).postJson(org.mockito.ArgumentMatchers.eq(SMS_URL), any());
    }

    @Test
    void escalationIsSkippedWhenNoChatChannelConfiguredAndTypeNotListed() {
        enableEscalation("DISPUTE_SLA_OVERDUE", ONCALL_JSON, SMS_URL, PHONE_URL);

        dispatcher.send("DEVICE_TEMP_HIGH", "标题", "正文", Map.of());

        verify(dispatcher, never()).postJson(anyString(), any());
    }

    @Test
    void escalationPayloadShape_isStable() {
        OnCallRoster.Entry entry = OnCallRoster.parse(ONCALL_JSON).currentAt(MONDAY_10).orElseThrow();

        Map<String, Object> body = OpsAlertDispatcher.escalationPayload(
                "T", "题", "文", entry);

        assertEquals(List.of("type", "title", "message", "onCall", "phoneNumber"),
                List.copyOf(body.keySet()));
        assertNotNull(body.get("phoneNumber"));
        assertFalse(body.get("phoneNumber").toString().isBlank());
    }
}
