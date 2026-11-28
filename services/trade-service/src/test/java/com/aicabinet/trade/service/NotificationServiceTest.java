package com.aicabinet.trade.service;

import com.aicabinet.common.dto.NotificationDispatchMessage;
import com.aicabinet.trade.domain.NotificationLog;
import com.aicabinet.trade.domain.NotificationTemplate;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.NotificationTemplateMapper;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    @Mock private NotificationTemplateMapper templateRepository;
    @Mock private NotificationLogMapper logRepository;
    @Mock private ConsumerNotifyPrefService notifyPrefService;
    @Mock private NotificationDispatchOutboxService outboxService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private AdminAuditService auditService;

    private NotificationService service() {
        when(distributedLockService.tryLock(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq(60L), org.mockito.ArgumentMatchers.eq(5L)))
                .thenReturn(true);
        NotificationService svc = new NotificationService(templateRepository, logRepository,
                notifyPrefService, outboxService, distributedLockService, auditService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "self", svc);
        return svc;
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
        NotificationService service = service();
        when(templateRepository.findByCode("order_paid")).thenReturn(Optional.of(template("IN_APP", "ORDER")));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);

        service.notifyConsumer(100L, "order_paid",
                Map.of("orderId", "O1", "amount", "12"), "ORDER", "O1");

        verify(logRepository).insertIgnore(any(NotificationLog.class));
        verify(outboxService, never()).enqueue(any());
    }

    @Test
    void send_shouldEnqueueAndDispatchExternalImmediatelyWithoutTransaction() {
        NotificationService service = service();
        when(templateRepository.findByCode("order_paid"))
                .thenReturn(Optional.of(template("IN_APP,WECHAT_SUBSCRIBE,SMS", "ORDER")));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);

        service.notifyConsumer(100L, "order_paid",
                Map.of("orderId", "O1", "amount", "12"), "ORDER", "O1");

        verify(logRepository).insertIgnore(any(NotificationLog.class));
        ArgumentCaptor<NotificationDispatchMessage> captor =
                ArgumentCaptor.forClass(NotificationDispatchMessage.class);
        verify(outboxService).enqueue(captor.capture());
        assertEquals("order_paid", captor.getValue().templateCode());
        assertEquals(100L, captor.getValue().userId());
        verify(outboxService).dispatchByKey(captor.getValue().clientMsgId());
    }

    @Test
    void send_shouldUseDeterministicClientMsgId() {
        NotificationService service = service();
        when(templateRepository.findByCode("order_paid"))
                .thenReturn(Optional.of(template("IN_APP,WECHAT_SUBSCRIBE", "ORDER")));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);

        service.notifyConsumer(100L, "order_paid",
                Map.of("orderId", "O1", "amount", "12"), "ORDER", "O1");
        service.notifyConsumer(100L, "order_paid",
                Map.of("orderId", "O1", "amount", "12"), "ORDER", "O1");

        ArgumentCaptor<NotificationDispatchMessage> captor =
                ArgumentCaptor.forClass(NotificationDispatchMessage.class);
        verify(outboxService, org.mockito.Mockito.times(2)).enqueue(captor.capture());
        assertEquals(captor.getAllValues().get(0).clientMsgId(), captor.getAllValues().get(1).clientMsgId());
        ArgumentCaptor<NotificationLog> inApp = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository, org.mockito.Mockito.times(2)).insertIgnore(inApp.capture());
        assertEquals("inapp:" + captor.getValue().clientMsgId(), inApp.getValue().getClientMsgId());
        assertEquals(inApp.getAllValues().get(0).getClientMsgId(), inApp.getAllValues().get(1).getClientMsgId());
    }

    @Test
    void send_externalDispatch_waitsForCommit() {
        NotificationService service = service();
        when(templateRepository.findByCode("order_paid"))
                .thenReturn(Optional.of(template("IN_APP,WECHAT_SUBSCRIBE,SMS", "ORDER")));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.notifyConsumer(100L, "order_paid",
                    Map.of("orderId", "O1", "amount", "12"), "ORDER", "O1");
            ArgumentCaptor<NotificationDispatchMessage> captor =
                    ArgumentCaptor.forClass(NotificationDispatchMessage.class);
            verify(outboxService).enqueue(captor.capture());
            verify(outboxService, never()).dispatchByKey(any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(outboxService).dispatchByKey(captor.getValue().clientMsgId());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void send_afterCommitFailure_isIsolatedFromCommittedBusiness() {
        NotificationService service = service();
        when(templateRepository.findByCode("order_paid"))
                .thenReturn(Optional.of(template("IN_APP,WECHAT_SUBSCRIBE", "ORDER")));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("broker down"))
                .when(outboxService).dispatchByKey(any());
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.notifyConsumer(100L, "order_paid",
                    Map.of("orderId", "O1", "amount", "12"), "ORDER", "O1");
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                    TransactionSynchronizationManager.getSynchronizations()
                            .forEach(TransactionSynchronization::afterCommit));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void send_outboxEnqueueFailure_fallsBackToDirectDispatch() {
        NotificationService service = service();
        when(templateRepository.findByCode("order_paid"))
                .thenReturn(Optional.of(template("IN_APP,WECHAT_SUBSCRIBE", "ORDER")));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException("outbox down"))
                .when(outboxService).enqueue(any());

        service.notifyConsumer(100L, "order_paid",
                Map.of("orderId", "O1", "amount", "12"), "ORDER", "O1");

        verify(outboxService).dispatchDirect(any(NotificationDispatchMessage.class));
    }

    @Test
    void send_prefDisabled_stillPersistsInAppButSkipsExternal() {
        NotificationService service = service();
        when(templateRepository.findByCode("order_paid"))
                .thenReturn(Optional.of(template("IN_APP,WECHAT_SUBSCRIBE,SMS", "ORDER")));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(false);

        service.notifyConsumer(100L, "order_paid",
                Map.of("orderId", "O1", "amount", "12"), "ORDER", "O1");

        verify(logRepository).insertIgnore(any(NotificationLog.class));
        verify(outboxService, never()).enqueue(any());
    }

    @Test
    void refundNotification_withoutTransaction_enqueuesAndDispatches() {
        NotificationService service = service();
        NotificationTemplate refund = template("IN_APP,WECHAT_SUBSCRIBE", "ORDER");
        refund.setTemplateCode("refund_success");
        refund.setTitleTemplate("退款成功");
        refund.setBodyTemplate("您的订单 {orderId} 已退款 {amount} 元");
        when(templateRepository.findByCode("refund_success")).thenReturn(Optional.of(refund));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);

        service.notifyRefundAfterCommit(100L, "O1", 550, true);

        ArgumentCaptor<NotificationDispatchMessage> captor =
                ArgumentCaptor.forClass(NotificationDispatchMessage.class);
        verify(outboxService).enqueue(captor.capture());
        assertEquals("REFUND", captor.getValue().bizType());
        assertEquals("5.50", captor.getValue().smsParams().get("amount"));
        verify(outboxService).dispatchByKey(captor.getValue().clientMsgId());
    }

    @Test
    void refundNotification_insideTransaction_persistsInAppNowAndDefersExternal() {
        NotificationService service = service();
        NotificationTemplate refund = template("IN_APP,WECHAT_SUBSCRIBE", "ORDER");
        refund.setTemplateCode("refund_success");
        refund.setTitleTemplate("退款成功");
        refund.setBodyTemplate("您的订单 {orderId} 已退款 {amount} 元");
        when(templateRepository.findByCode("refund_success")).thenReturn(Optional.of(refund));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.notifyRefundAfterCommit(100L, "R1", 550, false);
            verify(logRepository).insertIgnore(any(NotificationLog.class));
            ArgumentCaptor<NotificationDispatchMessage> captor =
                    ArgumentCaptor.forClass(NotificationDispatchMessage.class);
            verify(outboxService).enqueue(captor.capture());
            verify(outboxService, never()).dispatchByKey(any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(outboxService).dispatchByKey(captor.getValue().clientMsgId());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void refundNotification_sameSemanticKey_resumeUsesSameInAppAndOutboxKeys() {
        NotificationService service = service();
        NotificationTemplate refund = template("IN_APP,WECHAT_SUBSCRIBE", "ORDER");
        refund.setTemplateCode("refund_success");
        refund.setTitleTemplate("退款成功");
        refund.setBodyTemplate("您的订单 {orderId} 已退款 {amount} 元");
        when(templateRepository.findByCode("refund_success")).thenReturn(Optional.of(refund));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);

        service.notifyRefundAfterCommit(100L, "O-RESUME", 550, true, "FULL:O-RESUME");
        service.notifyRefundAfterCommit(100L, "O-RESUME", 550, true, "FULL:O-RESUME");

        ArgumentCaptor<NotificationDispatchMessage> messages =
                ArgumentCaptor.forClass(NotificationDispatchMessage.class);
        verify(outboxService, times(2)).enqueue(messages.capture());
        assertEquals(messages.getAllValues().get(0).clientMsgId(),
                messages.getAllValues().get(1).clientMsgId());
        verify(outboxService, times(2)).dispatchByKey(messages.getValue().clientMsgId());
        ArgumentCaptor<NotificationLog> logs = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository, times(2)).insertIgnore(logs.capture());
        assertEquals("inapp:" + messages.getValue().clientMsgId(), logs.getValue().getClientMsgId());
        assertEquals(logs.getAllValues().get(0).getClientMsgId(), logs.getAllValues().get(1).getClientMsgId());
    }

    @Test
    void refundNotification_differentPartialKeys_sameOrderAndAmountBothDeliver() {
        NotificationService service = service();
        NotificationTemplate refund = template("IN_APP,WECHAT_SUBSCRIBE", "ORDER");
        refund.setTemplateCode("refund_success");
        refund.setTitleTemplate("退款成功");
        refund.setBodyTemplate("您的订单 {orderId} 已退款 {amount} 元");
        when(templateRepository.findByCode("refund_success")).thenReturn(Optional.of(refund));
        when(notifyPrefService.isEnabled(100L, "ORDER")).thenReturn(true);

        service.notifyRefundAfterCommit(100L, "O-PART", 200, true, "PARTIAL:O-PART:lines-a");
        service.notifyRefundAfterCommit(100L, "O-PART", 200, true, "PARTIAL:O-PART:lines-b");

        ArgumentCaptor<NotificationDispatchMessage> messages =
                ArgumentCaptor.forClass(NotificationDispatchMessage.class);
        verify(outboxService, times(2)).enqueue(messages.capture());
        assertNotEquals(messages.getAllValues().get(0).clientMsgId(),
                messages.getAllValues().get(1).clientMsgId());
        verify(outboxService).dispatchByKey(messages.getAllValues().get(0).clientMsgId());
        verify(outboxService).dispatchByKey(messages.getAllValues().get(1).clientMsgId());
        ArgumentCaptor<NotificationLog> logs = ArgumentCaptor.forClass(NotificationLog.class);
        verify(logRepository, times(2)).insertIgnore(logs.capture());
        assertNotEquals(logs.getAllValues().get(0).getClientMsgId(),
                logs.getAllValues().get(1).getClientMsgId());
    }

    @Test
    void updateManual_shouldUpdateTitleAndBody() {
        NotificationService service = service();
        NotificationLog existing = new NotificationLog();
        existing.setId(9L);
        existing.setTitle("旧标题");
        existing.setBody("旧内容");
        existing.setAudience("CONSUMER");
        when(logRepository.findByIdForUpdate(9L)).thenReturn(Optional.of(existing));

        var dto = service.updateManual(1L, 9L,
                new com.aicabinet.common.dto.AdminUpdateNotificationRequest("新标题", "新内容"));

        assertEquals("新标题", dto.title());
        assertEquals("新内容", dto.body());
        verify(logRepository).updateById(existing);
        verify(auditService).appendLog(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("NOTIFY_UPDATE"),
                org.mockito.ArgumentMatchers.eq("NOTIFICATION_LOG"),
                org.mockito.ArgumentMatchers.eq("9"),
                org.mockito.ArgumentMatchers.contains("title=新标题"));
    }

    @Test
    void deleteManual_shouldDeleteById() {
        NotificationService service = service();
        NotificationLog existing = new NotificationLog();
        existing.setId(11L);
        when(logRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(existing));

        service.deleteManual(1L, 11L);

        verify(logRepository).deleteById(11L);
        verify(auditService).appendLog(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("NOTIFY_DELETE"),
                org.mockito.ArgumentMatchers.eq("NOTIFICATION_LOG"),
                org.mockito.ArgumentMatchers.eq("11"),
                org.mockito.ArgumentMatchers.anyString());
    }

    // ── MA-154：消费者「标记已读」的归属闸（第一轮核过有闸，但此前无任何用例钉住）──

    @Test
    void markConsumerRead_foreignUser_forbiddenAndNotMarked() {
        NotificationService service = service();
        NotificationLog other = new NotificationLog();
        other.setId(77L);
        other.setUserId(2002L);
        when(logRepository.findByIdForUpdate(77L)).thenReturn(Optional.of(other));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.markConsumerRead(1001L, 77L));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertNull(other.getReadAt(), "被拒时不得先把 readAt 写上");
        verify(logRepository, never()).save(any(NotificationLog.class));
    }

    @Test
    void markConsumerRead_owner_marksRead() {
        // 正向对照：本人必须过得去，否则上面那条「403」可能是闸写反了造成的假绿
        NotificationService service = service();
        NotificationLog mine = new NotificationLog();
        mine.setId(78L);
        mine.setUserId(1001L);
        when(logRepository.findByIdForUpdate(78L)).thenReturn(Optional.of(mine));

        service.markConsumerRead(1001L, 78L);

        assertNotNull(mine.getReadAt());
        verify(logRepository).save(mine);
    }
}
