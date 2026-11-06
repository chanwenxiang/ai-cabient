package com.aicabinet.trade.service;

import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.config.NotificationProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.NotificationTemplate;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.NotificationTemplateMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.sms.WebhookSmsSender;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.aicabinet.trade.sms.AliyunSmsSender;
import com.aicabinet.trade.wechat.MiniAppTarget;

@ExtendWith(MockitoExtension.class)
class ExternalNotificationDispatcherTest {

    @Mock private NotificationLogMapper logRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private NotificationTemplateMapper templateRepository;
    @Mock private WeChatMiniAppClient weChatMiniAppClient;
    @Mock private WebhookSmsSender smsSender;
    @Mock private AliyunSmsSender aliyunSmsSender;

    private static final WeChatMiniAppProperties WECHAT_PROPS =
            new WeChatMiniAppProperties(false, null, null, "merchant-tpl", "pages/alerts/alerts",
                    "consumer-tpl", "pages/messages/messages", null, null, null, null);

    private ExternalNotificationDispatcher dispatcher(boolean wechat, boolean sms) {
        return new ExternalNotificationDispatcher(logRepository, userInfoRepository, templateRepository,
                weChatMiniAppClient, WECHAT_PROPS, smsSender,
                new NotifyChannelSwitchService(org.mockito.Mockito.mock(SystemConfigService.class), new NotificationProperties(wechat, sms, false)),
                aliyunSmsSender);
    }

    private static NotificationDispatchMessage message() {
        return new NotificationDispatchMessage("order_paid", 100L, "订单支付成功",
                "您的订单 O1 已支付 12 元", "ORDER", "O1");
    }

    private static NotificationTemplate template(String channels) {
        NotificationTemplate tpl = new NotificationTemplate();
        tpl.setTemplateCode("order_paid");
        tpl.setChannels(channels);
        return tpl;
    }

    private static NotificationTemplate template(String code, String channels, String aliyunTemplateCode) {
        NotificationTemplate tpl = new NotificationTemplate();
        tpl.setTemplateCode(code);
        tpl.setChannels(channels);
        tpl.setAliyunTemplateCode(aliyunTemplateCode);
        return tpl;
    }

    private static UserInfo user(String openId, String phone) {
        UserInfo u = new UserInfo();
        u.setUserId(100L);
        u.setWxOpenId(openId);
        u.setPhoneNumber(phone);
        return u;
    }

    @Test
    void dispatch_shouldPushWechatWhenEnabledAndOpenIdBound() {
        ExternalNotificationDispatcher dispatcher = dispatcher(true, false);
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user("openid-1", null)));
        when(weChatMiniAppClient.sendSubscribeMessage(anyString(), anyString(), anyString(), anyMap(), eq(MiniAppTarget.CONSUMER)))
                .thenReturn(true);

        dispatcher.dispatch(message());

        verify(weChatMiniAppClient).sendSubscribeMessage(
                eq("openid-1"), eq("consumer-tpl"), eq("pages/messages/messages"), anyMap(), eq(MiniAppTarget.CONSUMER));
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository).save(captor.capture());
        assertEquals("WECHAT_SUBSCRIBE", captor.getValue().getChannel());
    }

    @Test
    void dispatch_aliyunTemplatePath_mapsParamsAndUsesRegisteredCode() {
        // V441：CODE 来自模板行自身（未登记就不发），参数只保留模板声明的两组（不再注入 service_phone）
        ExternalNotificationDispatcher dispatcher = dispatcher(false, true);
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user(null, "13800138000")));
        when(aliyunSmsSender.isConfigured()).thenReturn(true);
        when(templateRepository.findByCode("unpaid_order_dunning"))
                .thenReturn(Optional.of(template("unpaid_order_dunning", "IN_APP,WECHAT_SUBSCRIBE,SMS", "SMS_512512665")));

        NotificationDispatchMessage msg = new NotificationDispatchMessage("unpaid_order_dunning", 100L,
                "订单未支付提醒", "您的订单 O1 尚未支付 12.5 元", "ORDER", "O1",
                Map.of("orderId", "O1", "amount", "12.5"));
        dispatcher.dispatch(msg);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, String>> captor =
                ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        verify(aliyunSmsSender).sendMessage(eq("13800138000"), eq("SMS_512512665"), captor.capture());
        assertEquals(Map.of("order_no", "O1", "amount", "12.5"), captor.getValue());
        verify(smsSender, never()).sendMessage(anyString(), anyString());
        ArgumentCaptor<NotificationLog> logCaptor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository).save(logCaptor.capture());
        assertEquals("SMS", logCaptor.getValue().getChannel());
    }

    @Test
    void dispatch_otherTemplatesNeverReuseDunningAliyunCode() {
        // 回归（2026-11-04）：order_paid 与催缴同键 {orderId, amount}，此前会被套成催缴文案；
        // 现在必须用该模板自己登记的 CODE。
        ExternalNotificationDispatcher dispatcher = dispatcher(false, true);
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user(null, "13800138000")));
        when(aliyunSmsSender.isConfigured()).thenReturn(true);
        when(templateRepository.findByCode("order_paid"))
                .thenReturn(Optional.of(template("order_paid", "IN_APP,WECHAT_SUBSCRIBE,SMS", "SMS_ORDER_PAID")));

        NotificationDispatchMessage msg = new NotificationDispatchMessage("order_paid", 100L,
                "订单支付成功", "您的订单 O1 已支付 12 元", "ORDER", "O1",
                Map.of("orderId", "O1", "amount", "12"));
        dispatcher.dispatch(msg);

        verify(aliyunSmsSender).sendMessage(eq("13800138000"), eq("SMS_ORDER_PAID"), anyMap());
        verify(aliyunSmsSender, never()).sendMessage(anyString(), eq("SMS_512512665"), anyMap());
    }

    @Test
    void dispatch_templateWithoutAliyunCodeFallsBackToWebhook() {
        // 模板 channels 含 SMS 但没登记阿里云 CODE ⇒ 不得猜用别的模板，回退既有 webhook 文本通道
        ExternalNotificationDispatcher dispatcher = dispatcher(false, true);
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user(null, "13800138000")));
        when(templateRepository.findByCode("unpaid_order_dunning"))
                .thenReturn(Optional.of(template("unpaid_order_dunning", "IN_APP,SMS", null)));

        NotificationDispatchMessage msg = new NotificationDispatchMessage("unpaid_order_dunning", 100L,
                "订单未支付提醒", "您的订单 O1 尚未支付 12.5 元", "ORDER", "O1",
                Map.of("orderId", "O1", "amount", "12.5"));
        dispatcher.dispatch(msg);

        verify(aliyunSmsSender, never()).sendMessage(anyString(), anyString(), anyMap());
        verify(smsSender).sendMessage(eq("13800138000"), anyString());
    }

    @Test
    void dispatch_webhookFallback_whenAliyunNotConfigured() {
        // 已登记 CODE 但阿里云未配齐 ⇒ 回退 webhook 文本通道（既有行为不变）
        ExternalNotificationDispatcher dispatcher = dispatcher(false, true);
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user(null, "13800138000")));
        when(templateRepository.findByCode("unpaid_order_dunning"))
                .thenReturn(Optional.of(template("unpaid_order_dunning", "IN_APP,SMS", "SMS_512512665")));
        when(aliyunSmsSender.isConfigured()).thenReturn(false);

        NotificationDispatchMessage msg = new NotificationDispatchMessage("unpaid_order_dunning", 100L,
                "订单未支付提醒", "您的订单 O1 尚未支付 12.5 元", "ORDER", "O1",
                Map.of("orderId", "O1", "amount", "12.5"));
        dispatcher.dispatch(msg);

        verify(aliyunSmsSender, never()).sendMessage(anyString(), anyString(), anyMap());
        verify(smsSender).sendMessage(eq("13800138000"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatch_wechatThingFieldsClippedTo20Chars() {
        // 2026-10-20：微信 thing 字段限 20 字符，超长整条拒发——发送侧必须兜底截断
        ExternalNotificationDispatcher dispatcher = dispatcher(true, false);
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user("openid-1", null)));
        when(weChatMiniAppClient.sendSubscribeMessage(anyString(), anyString(), anyString(), anyMap(), eq(MiniAppTarget.CONSUMER)))
                .thenReturn(true);

        NotificationDispatchMessage longMsg = new NotificationDispatchMessage("order_paid", 100L,
                "这是一个超过二十个字符的超长订单通知标题用于验证截断逻辑生效",
                "这也是一段超过二十个字符的超长正文内容用于验证 thing 字段截断兜底是否正常生效", "ORDER", "O1");
        dispatcher.dispatch(longMsg);

        ArgumentCaptor<Map<String, String>> payloadCaptor =
                ArgumentCaptor.forClass((Class<Map<String, String>>) (Class<?>) Map.class);
        verify(weChatMiniAppClient).sendSubscribeMessage(
                anyString(), anyString(), anyString(), payloadCaptor.capture(), eq(MiniAppTarget.CONSUMER));
        assertEquals(20, payloadCaptor.getValue().get("thing1").length());
        assertEquals(20, payloadCaptor.getValue().get("thing2").length());
    }

    @Test
    void dispatch_shouldPushSmsWhenEnabledAndPhoneBound() {
        ExternalNotificationDispatcher dispatcher = dispatcher(false, true);
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user(null, "13800138000")));

        dispatcher.dispatch(message());

        verify(smsSender).sendMessage(eq("13800138000"), anyString());
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository).save(captor.capture());
        assertEquals("SMS", captor.getValue().getChannel());
    }

    @Test
    void dispatch_shouldDoNothingWhenChannelsDisabled() {
        ExternalNotificationDispatcher dispatcher = dispatcher(false, false);

        dispatcher.dispatch(message());

        verify(logRepository, never()).save(any(NotificationLog.class));
        verify(weChatMiniAppClient, never()).sendSubscribeMessage(anyString(), anyString(), anyString(), anyMap(), any());
        verify(smsSender, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void dispatch_shouldSkipWhenUserMissing() {
        ExternalNotificationDispatcher dispatcher = dispatcher(true, true);
        when(userInfoRepository.findById(100L)).thenReturn(Optional.empty());

        dispatcher.dispatch(message());

        verify(weChatMiniAppClient, never()).sendSubscribeMessage(anyString(), anyString(), anyString(), anyMap(), any());
        verify(smsSender, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void dispatch_wechatOnlyTemplate_shouldNotSendSms() {
        // M09：模板 channels 仅 IN_APP,WECHAT_SUBSCRIBE（如 coupon_expiring），全局 sms 开启也不发短信
        ExternalNotificationDispatcher dispatcher = dispatcher(true, true);
        when(templateRepository.findByCode("order_paid")).thenReturn(Optional.of(template("IN_APP,WECHAT_SUBSCRIBE")));
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user("openid-1", "13800138000")));
        when(weChatMiniAppClient.sendSubscribeMessage(anyString(), anyString(), anyString(), anyMap(), eq(MiniAppTarget.CONSUMER)))
                .thenReturn(true);

        dispatcher.dispatch(message());

        verify(smsSender, never()).sendMessage(anyString(), anyString());
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository).save(captor.capture());
        assertEquals("WECHAT_SUBSCRIBE", captor.getValue().getChannel());
    }

    @Test
    void dispatch_smsChannelTemplate_shouldSendSms() {
        // M09：模板 channels 含 SMS 时短信照常发送
        ExternalNotificationDispatcher dispatcher = dispatcher(false, true);
        when(templateRepository.findByCode("order_paid")).thenReturn(Optional.of(template("IN_APP,WECHAT_SUBSCRIBE,SMS")));
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user(null, "13800138000")));

        dispatcher.dispatch(message());

        verify(smsSender).sendMessage(eq("13800138000"), anyString());
    }

    // ── SENT 语义三钉（2026-11-06 拍板 B 档：钉语义 + 改文案，回执采集暂不接）──

    @Test
    void sentStatusMeansChannelAccepted_notUserDelivery() {
        // 钉「SENT 的唯一含义 = 渠道已受理」：阿里云返回 OK 即写 SENT，与用户是否收到无关
        ExternalNotificationDispatcher dispatcher = dispatcher(false, true);
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user(null, "13800138000")));
        when(templateRepository.findByCode("unpaid_order_dunning"))
                .thenReturn(Optional.of(template("unpaid_order_dunning", "IN_APP,SMS", "SMS_512512665")));
        when(aliyunSmsSender.isConfigured()).thenReturn(true);

        dispatcher.dispatch(new NotificationDispatchMessage("unpaid_order_dunning", 100L,
                "订单未支付提醒", "您的订单 O1 尚未支付 12.5 元", "ORDER", "O1",
                Map.of("orderId", "O1", "amount", "12.5")));

        verify(aliyunSmsSender).sendMessage(eq("13800138000"), eq("SMS_512512665"), anyMap());
        ArgumentCaptor<NotificationLog> saved = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository).save(saved.capture());
        assertEquals("SENT", saved.getValue().getStatus());
    }

    @Test
    void noSentLogWhenChannelCallFails() {
        // 钉「渠道失败不得落 SENT」：dispatch 按设计吞掉渠道异常（只记 warn，不影响其它渠道），
        // 但 saveLog 在发送调用之后 ⇒ 一行日志都不许写。SENT 不是「我们尝试过」。
        ExternalNotificationDispatcher dispatcher = dispatcher(false, true);
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user(null, "13800138000")));
        when(templateRepository.findByCode("order_paid"))
                .thenReturn(Optional.of(template("IN_APP,SMS")));
        doThrow(new IllegalStateException("webhook down")).when(smsSender).sendMessage(anyString(), anyString());

        dispatcher.dispatch(message());

        verify(logRepository, never()).save(any());
    }

    @Test
    void noSentLogWhenWechatRejectsSubscribeSend() {
        // 微信侧同一条契约：sendSubscribeMessage 返回 false（errcode≠0）⇒ 无受理、不落 SENT
        ExternalNotificationDispatcher dispatcher = dispatcher(true, false);
        when(userInfoRepository.findById(100L)).thenReturn(Optional.of(user("openid-1", null)));
        when(weChatMiniAppClient.sendSubscribeMessage(anyString(), anyString(), anyString(), anyMap(),
                eq(MiniAppTarget.CONSUMER))).thenReturn(false);

        dispatcher.dispatch(message());

        verify(logRepository, never()).save(any());
    }

    @Test
    void notificationLogCarriesNoDeliveryReceiptSoSentCannotMeanArrival() {
        // 表里没有到达/回执列 ⇒ 任何把 SENT 读成「到达率」的界面/报表都是错的。
        // 将来真要接回执，必须同时新增列、改这条钉测与 javadoc 语义，不能悄悄把 SENT 升级成「已送达」。
        for (java.lang.reflect.Field f : NotificationLog.class.getDeclaredFields()) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            assertTrue(!n.contains("deliver") && !n.contains("receipt") && !n.contains("arrive"),
                    () -> "notification_log 出现了投递/回执列：" + f.getName() + " ⇒ SENT 的语义必须重新定义");
        }
    }
}
