package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.VideoAttachRequest;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.config.VisionAsyncProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.event.DomainEventPublisher;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionAttachVideoSettleTest {

    @Mock ShoppingSessionMapper repository;
    @Mock DeviceServiceClient deviceClient;
    @Mock UserValidationService userValidationService;
    @Mock DeviceValidationService deviceValidationService;
    @Mock SettlementService settlementService;
    @Mock VisionAsyncProperties visionAsyncProperties;
    @Mock CabinetMetrics cabinetMetrics;
    @Mock DomainEventPublisher domainEventPublisher;
    @Mock GravitySettlementHelper gravityHelper;
    @Mock RestockSnapshotService restockSnapshotService;
    @Mock OpsExceptionService opsExceptionService;
    @Mock com.aicabinet.trade.mapper.UserInfoMapper userInfoRepository;
    @Mock com.aicabinet.trade.mapper.CabinetOrderMapper orderRepository;
    @Mock ConsumerPreauthService consumerPreauthService;
    @Mock DistributedLockService distributedLockService;
    @Mock ScheduledTaskService taskService;

    private SessionService service;

    @BeforeEach
    void setUp() {
        service = new SessionService(repository, deviceClient, userValidationService, deviceValidationService,
                settlementService, visionAsyncProperties, cabinetMetrics, domainEventPublisher,
                gravityHelper, restockSnapshotService, null, opsExceptionService, userInfoRepository, orderRepository,
                null, null, consumerPreauthService, null, distributedLockService, taskService, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    /** Q6: WAITING_UPLOAD → attachVideo 结算一次；再次 attach 不二次 settle。 */
    @Test
    void attachVideo_waitingUpload_settlesOnce() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-Q6");
        session.setDeviceId("CAB-001");
        session.setUserId(7L);
        session.setState(SessionState.WAITING_UPLOAD);

        when(repository.findByIdForUpdate("S-Q6")).thenReturn(Optional.of(session));
        when(visionAsyncProperties.enabled()).thenReturn(false);
        when(settlementService.settle(session)).thenReturn(new OrderDto(
                "O-Q6", "S-Q6", 7L, "CAB-001", 100, List.of(), "PAID",
                "BALANCE", null, null, null, Instant.now()));

        var first = service.attachVideo(new VideoAttachRequest("S-Q6", "CAB-001", "s3://v.mp4", "UPLOADED", null, null));
        assertEquals(SessionState.COMPLETED, first.state());
        assertEquals(SessionState.COMPLETED, session.getState());
        verify(settlementService, times(1)).settle(session);

        var second = service.attachVideo(new VideoAttachRequest("S-Q6", "CAB-001", "s3://v2.mp4", "UPLOADED", null, null));
        assertEquals(SessionState.COMPLETED, second.state());
        verify(settlementService, times(1)).settle(any());
    }
}
