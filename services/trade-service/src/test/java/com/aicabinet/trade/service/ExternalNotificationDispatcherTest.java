package com.aicabinet.trade.service;

import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.config.NotificationProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.sms.WebhookSmsSender;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExternalNotificationDispatcherTest {

    @Mock private NotificationLogMapper logRepository;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private WeChatMiniAppClient weChatMiniAppClient;
    @Mock private WebhookSmsSender smsSender;

    private static final WeChatMiniAppProperties WECHAT_PROPS =
            new WeChatMiniAppProperties(false, null, null, "merchant-tpl", "pages/alerts/alerts",
                    "consumer-tpl", "pages/messages/messages");

    private ExternalNotificationDispatcher dispatcher(boolean wechat, boolean sms) {
        return new ExternalNotificationDispatcher(logRepository, userInfoRepository,
                weChatMiniAppClient, WECHAT_PROPS, smsSender,
                new NotificationProperties(wechat, sms, false));
    }

    private static NotificationDispatchMessage message() {
        return new NotificationDispatchMessage("order_paid", 100L, "订单支付成功",
                "您的订单 O1 已支付 12 元", "ORDER", "O1");
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
        when(weChatMiniAppClient.sendSubscribeMessage(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(true);

        dispatcher.dispatch(message());

        verify(weChatMiniAppClient).sendSubscribeMessage(
                eq("openid-1"), eq("consumer-tpl"), eq("pages/messages/messages"), anyMap());
        ArgumentCaptor<NotificationLog> captor = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository).save(captor.capture());
        assertEquals("WECHAT_SUBSCRIBE", captor.getValue().getChannel());
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
        verify(weChatMiniAppClient, never()).sendSubscribeMessage(anyString(), anyString(), anyString(), anyMap());
        verify(smsSender, never()).sendMessage(anyString(), anyString());
    }

    @Test
    void dispatch_shouldSkipWhenUserMissing() {
        ExternalNotificationDispatcher dispatcher = dispatcher(true, true);
        when(userInfoRepository.findById(100L)).thenReturn(Optional.empty());

        dispatcher.dispatch(message());

        verify(weChatMiniAppClient, never()).sendSubscribeMessage(anyString(), anyString(), anyString(), anyMap());
        verify(smsSender, never()).sendMessage(anyString(), anyString());
    }
}
