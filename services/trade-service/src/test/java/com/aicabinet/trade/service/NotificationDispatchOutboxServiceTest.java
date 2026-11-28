package com.aicabinet.trade.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.domain.NotificationDispatchOutbox;
import com.aicabinet.trade.mapper.NotificationDispatchOutboxMapper;
import com.aicabinet.trade.messaging.NotificationDispatchProducer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class NotificationDispatchOutboxServiceTest {

    @Mock private NotificationDispatchOutboxMapper outboxRepository;
    @Mock private ExternalNotificationDispatcher externalDispatcher;
    @Mock private NotifyChannelSwitchService channelSwitch;
    @Mock private ObjectProvider<NotificationDispatchProducer> producerProvider;
    @Mock private DistributedLockService distributedLockService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private NotificationDispatchOutboxService service;

    @BeforeEach
    void setUp() {
        service = new NotificationDispatchOutboxService(outboxRepository, objectMapper,
                externalDispatcher, channelSwitch, producerProvider, distributedLockService);
    }

    @Test
    void enqueue_usesStableKeyAndPersistsPayload() {
        NotificationDispatchMessage message = message("O-1");

        String key = service.enqueue(message);

        ArgumentCaptor<NotificationDispatchOutbox> captor =
                ArgumentCaptor.forClass(NotificationDispatchOutbox.class);
        verify(outboxRepository).insertIgnore(captor.capture());
        assertEquals(32, key.length());
        assertEquals(key, captor.getValue().getIdempotencyKey());
        assertEquals("PENDING", captor.getValue().getStatus());
        assertTrue(captor.getValue().getPayloadJson().contains("order_paid"));
    }

    @Test
    void dispatchDue_success_marksSent() throws Exception {
        NotificationDispatchOutbox row = row();
        when(outboxRepository.findDue(anyInt())).thenReturn(List.of(row));
        when(outboxRepository.findById(row.getId())).thenReturn(row);
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(0L))).thenReturn(true);
        when(externalDispatcher.dispatchSuccessful(any())).thenReturn(true);

        int sent = service.dispatchDue();

        assertEquals(1, sent);
        verify(outboxRepository).markSent(row.getId());
        verify(outboxRepository, never()).markFailed(anyLong(), any(), anyString());
    }

    @Test
    void dispatchDue_failure_marksFailedWithBackoff() throws Exception {
        NotificationDispatchOutbox row = row();
        when(outboxRepository.findDue(anyInt())).thenReturn(List.of(row));
        when(outboxRepository.findById(row.getId())).thenReturn(row);
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(0L))).thenReturn(true);
        when(externalDispatcher.dispatchSuccessful(any())).thenReturn(false);

        int sent = service.dispatchDue();

        assertEquals(0, sent);
        verify(outboxRepository).markFailed(eq(row.getId()), any(Instant.class), eq("外部渠道发送失败"));
        verify(outboxRepository, never()).markSent(anyLong());
    }

    @Test
    void dispatchDue_lockBusy_skipsWithoutSending() throws Exception {
        NotificationDispatchOutbox row = row();
        when(outboxRepository.findDue(anyInt())).thenReturn(List.of(row));
        when(outboxRepository.findById(row.getId())).thenReturn(row);
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(0L))).thenReturn(false);

        int sent = service.dispatchDue();

        assertEquals(0, sent);
        verify(externalDispatcher, never()).dispatchSuccessful(any());
        verify(outboxRepository, never()).markSent(anyLong());
    }

    @Test
    void dispatchByKey_alreadySent_skips() throws Exception {
        NotificationDispatchOutbox row = row();
        row.setStatus("SENT");
        when(outboxRepository.findByKey(row.getIdempotencyKey())).thenReturn(row);

        assertFalse(service.dispatchByKey(row.getIdempotencyKey()));

        verify(externalDispatcher, never()).dispatchSuccessful(any());
    }

    @Test
    void dispatchByKey_rechecksStatusAfterAcquiringLock() throws Exception {
        NotificationDispatchOutbox row = row();
        NotificationDispatchOutbox sent = row();
        sent.setStatus("SENT");
        when(outboxRepository.findByKey(row.getIdempotencyKey())).thenReturn(row);
        when(distributedLockService.tryLock(anyString(), eq(60L), eq(0L))).thenReturn(true);
        when(outboxRepository.findById(row.getId())).thenReturn(sent);

        assertFalse(service.dispatchByKey(row.getIdempotencyKey()));

        verify(externalDispatcher, never()).dispatchSuccessful(any());
        verify(outboxRepository, never()).markSent(anyLong());
    }

    @Test
    void idempotencyKey_usesBusinessEventNotRenderedCopy() throws Exception {
        NotificationDispatchMessage first = message("O-1");
        NotificationDispatchMessage rewritten = new NotificationDispatchMessage("order_paid", 100L,
                "新标题", "新正文", "ORDER", "O-1", Map.of(), "ignored", "CONSUMER", null);

        assertEquals(NotificationDispatchOutboxService.idempotencyKey(first),
                NotificationDispatchOutboxService.idempotencyKey(rewritten));
        assertFalse(NotificationDispatchOutboxService.idempotencyKey(first)
                .equals(NotificationDispatchOutboxService.idempotencyKey(message("O-2"))));
    }

    @Test
    void idempotencyKey_refundUsesStableClientMsgIdAcrossPartialLines() {
        NotificationDispatchMessage first = new NotificationDispatchMessage("refund_success", 100L,
                "退款成功", "退款 2.00 元", "REFUND", "O-1", Map.of(), "stable-a", "CONSUMER", null);
        NotificationDispatchMessage second = new NotificationDispatchMessage("refund_success", 100L,
                "退款成功", "退款 2.00 元", "REFUND", "O-1", Map.of(), "stable-b", "CONSUMER", null);

        assertEquals("stable-a", NotificationDispatchOutboxService.idempotencyKey(first));
        assertEquals("stable-b", NotificationDispatchOutboxService.idempotencyKey(second));
    }

    @Test
    void idempotencyKey_withoutBizId_keepsDistinctMessagesApart() {
        NotificationDispatchMessage first = new NotificationDispatchMessage("order_paid", 100L,
                "标题A", "正文A", "ORDER", null);
        NotificationDispatchMessage second = new NotificationDispatchMessage("order_paid", 100L,
                "标题B", "正文B", "ORDER", null);

        assertFalse(NotificationDispatchOutboxService.idempotencyKey(first)
                .equals(NotificationDispatchOutboxService.idempotencyKey(second)));
    }

    private static NotificationDispatchOutbox row() throws Exception {
        NotificationDispatchOutbox row = new NotificationDispatchOutbox();
        row.setId(1L);
        row.setIdempotencyKey("key-1");
        row.setPayloadJson(new ObjectMapper().writeValueAsString(message("O-1")));
        row.setStatus("PENDING");
        row.setRetryCount(0);
        row.setNextRetryAt(Instant.now());
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        return row;
    }

    private static NotificationDispatchMessage message(String orderId) {
        return new NotificationDispatchMessage("order_paid", 100L, "订单支付成功",
                "订单 " + orderId + " 已支付", "ORDER", orderId,
                Map.of("orderId", orderId, "amount", "12.00"), "client-1", "CONSUMER", null);
    }
}
