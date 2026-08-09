package com.aicabinet.trade.service;

import com.aicabinet.trade.config.NotificationProperties;
import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.NotificationTemplate;
import com.aicabinet.trade.messaging.NotificationDispatchProducer;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.NotificationTemplateMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
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
class NotificationServiceTest {

    @Mock private NotificationTemplateMapper templateRepository;
    @Mock private NotificationLogMapper logRepository;
    @Mock private ConsumerNotifyPrefService notifyPrefService;
    @Mock private ExternalNotificationDispatcher externalDispatcher;
    @Mock private ObjectProvider<NotificationDispatchProducer> producerProvider;
    @Mock private NotificationDispatchProducer producer;

    private NotificationService service(boolean async) {
        return new NotificationService(templateRepository, logRepository,
                new NotificationProperties(false, false, async), notifyPrefService,
                externalDispatcher, producerProvider);
    }

    private static NotificationTemplate template(String channels, String category) {
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateCode("order_paid");
        t.setTemplateName("订单支付成功");
        t.setChannel("IN_APP");
        t.setChannels(channels);
        t.setCategory(category);
        t.setTitleTemplate("订单支付成功");
        t.setBodyTemplate("您的订单 {orderId} 已支付 {amount} 元");
        t.setAudience("CONSUMER");
        return t;
    }

    @Test
    void send_shouldPersistInAppOnlyByDefault() {
        NotificationService service = service(false);
        when(templateRepository.findByCode("order_paid")).thenReturn(Optional.of(template("IN_APP", "ORDER")));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);

        service.notifyConsumer(100L, "order_paid",
                Map.of("orderId", "O1", "amount", "12"), "ORDER", "O1");

        verify(logRepository).save(any(NotificationLog.class));
        verify(externalDispatcher, never()).dispatch(any());
    }

    @Test
    void send_shouldDispatchExternalSynchronouslyWhenAsyncDisabled() {
        NotificationService service = service(false);
        when(templateRepository.findByCode("order_paid"))
                .thenReturn(Optional.of(template("IN_APP,WECHAT_SUBSCRIBE,SMS", "ORDER")));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);

        service.notifyConsumer(100L, "order_paid",
                Map.of("orderId", "O1", "amount", "12"), "ORDER", "O1");

        verify(logRepository).save(any(NotificationLog.class));
        ArgumentCaptor<NotificationDispatchMessage> captor =
                ArgumentCaptor.forClass(NotificationDispatchMessage.class);
        verify(externalDispatcher).dispatch(captor.capture());
        assertEquals("order_paid", captor.getValue().templateCode());
        assertEquals(100L, captor.getValue().userId());
        verify(producer, never()).publish(any());
    }

    @Test
    void send_shouldPublishToQueueWhenAsyncEnabled() {
        NotificationService service = service(true);
        when(templateRepository.findByCode("order_paid"))
                .thenReturn(Optional.of(template("IN_APP,WECHAT_SUBSCRIBE", "ORDER")));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);
        when(producerProvider.getIfAvailable()).thenReturn(producer);

        service.notifyConsumer(100L, "order_paid",
                Map.of("orderId", "O1", "amount", "12"), "ORDER", "O1");

        verify(logRepository).save(any(NotificationLog.class));
        ArgumentCaptor<NotificationDispatchMessage> captor =
                ArgumentCaptor.forClass(NotificationDispatchMessage.class);
        verify(producer).publish(captor.capture());
        assertEquals("order_paid", captor.getValue().templateCode());
        verify(externalDispatcher, never()).dispatch(any());
    }

    @Test
    void send_shouldSkipWhenPrefDisabled() {
        NotificationService service = service(false);
        when(templateRepository.findByCode("order_paid"))
                .thenReturn(Optional.of(template("IN_APP,WECHAT_SUBSCRIBE,SMS", "ORDER")));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(false);

        service.notifyConsumer(100L, "order_paid",
                Map.of("orderId", "O1", "amount", "12"), "ORDER", "O1");

        verify(logRepository, never()).save(any(NotificationLog.class));
        verify(externalDispatcher, never()).dispatch(any());
    }
}
