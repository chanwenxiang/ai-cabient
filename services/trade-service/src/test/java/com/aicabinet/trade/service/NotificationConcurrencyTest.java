package com.aicabinet.trade.service;

import com.aicabinet.trade.config.NotificationProperties;
import com.aicabinet.trade.mapper.NotificationLogMapper;
import com.aicabinet.trade.mapper.NotificationTemplateMapper;
import com.aicabinet.trade.messaging.NotificationDispatchProducer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationConcurrencyTest {

    @Mock private NotificationTemplateMapper templateRepository;
    @Mock private NotificationLogMapper logRepository;
    @Mock private ConsumerNotifyPrefService notifyPrefService;
    @Mock private ExternalNotificationDispatcher externalDispatcher;
    @Mock private ObjectProvider<NotificationDispatchProducer> producerProvider;
    @Mock private DistributedLockService distributedLockService;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(templateRepository, logRepository,
                new NotificationProperties(false, false, false), notifyPrefService,
                externalDispatcher, producerProvider, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void markConsumerRead_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                NotificationService.notificationLogLockKey(99L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.markConsumerRead(100L, 99L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void markConsumerAllRead_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                NotificationService.consumerNotificationLockKey(101L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.markConsumerAllRead(101L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void markMerchantRead_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                NotificationService.notificationLogLockKey(88L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.markMerchantRead("M-1", 88L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void markConsumerRead_whenRecordMissing_unlocksLock() {
        when(distributedLockService.tryLock(
                NotificationService.notificationLogLockKey(77L), 60L, 5L))
                .thenReturn(true);
        when(logRepository.findByIdForUpdate(77L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.markConsumerRead(100L, 77L));

        verify(distributedLockService).unlock(NotificationService.notificationLogLockKey(77L));
    }
}
