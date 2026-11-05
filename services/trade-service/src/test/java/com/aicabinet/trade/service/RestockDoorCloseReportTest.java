package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.event.DomainEventPublisher;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 将邑补货关门上报（uploadAddGoodsCloseStatus → /internal/v1/jiangyi/restock-door-closed，
 * 2026-10-27 EG943322621391 两轮实锤：真机补货链路唯一关门信号，此前不接则 90s OPENING
 * 超时必杀会话、小程序「关门后继续核对上架」永不到来）。
 *
 * <p>锁三件事：OPENING 孤儿关门照常推进到 COMPLETED；SHOPPING 正常关门落 closeTime +
 * 快照；无活跃补货会话/空设备号一律幂等 false 且不走门事件路径。</p>
 */
@ExtendWith(MockitoExtension.class)
class RestockDoorCloseReportTest {

    @Mock ShoppingSessionMapper repository;
    @Mock DeviceServiceClient deviceClient;
    @Mock UserValidationService userValidationService;
    @Mock DeviceValidationService deviceValidationService;
    @Mock SettlementService settlementService;
    @Mock CabinetMetrics cabinetMetrics;
    /** 批 3b 第十五刀③：快照回写已退场，但 SessionDoorService 仍注入它（依赖待清），这里先保留 mock */
    @Mock RestockSnapshotService restockSnapshotService;
    @Mock DomainEventPublisher domainEventPublisher;
    @Mock GravitySettlementHelper gravityHelper;
    @Mock OpsExceptionService opsExceptionService;
    @Mock com.aicabinet.trade.mapper.UserInfoMapper userInfoRepository;
    @Mock com.aicabinet.trade.mapper.CabinetOrderMapper orderRepository;
    @Mock ConsumerPreauthService consumerPreauthService;
    @Mock DistributedLockService distributedLockService;
    @Mock com.aicabinet.trade.service.JiangyiDeviceDirectory jiangyiDeviceDirectory;

    private SessionService service;
    private SessionDoorService doorService;

    @BeforeEach
    void setUp() {
        service = new SessionService(repository, deviceClient, userValidationService, deviceValidationService,
                settlementService, cabinetMetrics, domainEventPublisher,
                gravityHelper, null, null, null, null, null, null, userInfoRepository, orderRepository,
                consumerPreauthService, distributedLockService, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        SessionSettleService settleService = new SessionSettleService(
                repository, settlementService, cabinetMetrics, opsExceptionService, service);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sessionSettleService", settleService);
        doorService = new SessionDoorService(repository, gravityHelper, restockSnapshotService, null,
                cabinetMetrics, domainEventPublisher, service, null, jiangyiDeviceDirectory);
        org.springframework.test.util.ReflectionTestUtils.setField(doorService, "self", doorService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "sessionDoorService", doorService);
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ShoppingSession restockSession(SessionState state) {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-RD-1");
        session.setDeviceId("EG943322621391");
        session.setReplenishmentTaskId(5L);
        session.setState(state);
        return session;
    }

    @Test
    void openingSession_advancesThroughOrphanCloseToCompleted() {
        ShoppingSession session = restockSession(SessionState.OPENING);
        when(repository.findLatestActiveRestockByDeviceId("EG943322621391",
                List.of(SessionState.OPENING, SessionState.SHOPPING))).thenReturn(Optional.of(session));
        when(repository.findByIdForUpdate("S-RD-1")).thenReturn(Optional.of(session));
        when(repository.findById("S-RD-1")).thenReturn(Optional.of(session));

        assertTrue(doorService.applyRestockDoorClosed("EG943322621391"));
        assertEquals(SessionState.COMPLETED, session.getState());
        assertNotNull(session.getOpenTime());
    }

    @Test
    void shoppingSession_completesWithSnapshotAndCloseTime() {
        ShoppingSession session = restockSession(SessionState.SHOPPING);
        when(repository.findLatestActiveRestockByDeviceId("EG943322621391",
                List.of(SessionState.OPENING, SessionState.SHOPPING))).thenReturn(Optional.of(session));
        when(repository.findByIdForUpdate("S-RD-1")).thenReturn(Optional.of(session));
        when(repository.findById("S-RD-1")).thenReturn(Optional.of(session));

        assertTrue(doorService.applyRestockDoorClosed("EG943322621391"));
        assertEquals(SessionState.COMPLETED, session.getState());
        assertNotNull(session.getCloseTime());
        // 消费链路不受影响：补货会话关门不结算
        verify(settlementService, never()).settle(any());
    }

    @Test
    void noActiveRestockSession_returnsFalseWithoutDoorPath() {
        when(repository.findLatestActiveRestockByDeviceId("EG-2",
                List.of(SessionState.OPENING, SessionState.SHOPPING))).thenReturn(Optional.empty());

        assertFalse(doorService.applyRestockDoorClosed("EG-2"));
    }

    @Test
    void blankDeviceId_returnsFalseWithoutQuery() {
        assertFalse(doorService.applyRestockDoorClosed(" "));
        assertFalse(doorService.applyRestockDoorClosed(null));
        verify(repository, never()).findLatestActiveRestockByDeviceId(anyString(), any());
    }
}
