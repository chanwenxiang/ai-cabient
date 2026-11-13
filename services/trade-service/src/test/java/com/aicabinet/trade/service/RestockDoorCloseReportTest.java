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
        doorService = new SessionDoorService(repository, gravityHelper, null,
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

    /// P3-待定④（拍板材料 docs/PENDING_DECISIONS_5_2026-11-13.md）：运营重试不得落到补货会话——
    /// 旧实现仅校验状态，补货会话被异常扫描扫出后会被推进 consumer 结算链（订单/扣款/支付分副作用错挂）。
    @Test
    void opsRetry_onRestockSession_rejected409() {
        ShoppingSession session = restockSession(SessionState.FAILED);
        when(repository.findByIdForUpdate("S-RD-1")).thenReturn(Optional.of(session));

        org.springframework.web.server.ResponseStatusException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> service.retryForOperations("S-RD-1"));
        assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("补货会话不支持运营重试识别/结算", ex.getReason());
        assertEquals(SessionState.FAILED, session.getState(), "拒绝后不得改写会话状态");
    }

    /// 正向对照：消费者会话通过类型守卫进入重试。守卫专属断言 = clearFailReason 被调用
    /// （仅在守卫通过后才会执行）；守卫之后的结算链失败会把状态合法打回 FAILED，不断言终态。
    @Test
    void opsRetry_onConsumerSession_guardPasses() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-CD-1");
        session.setDeviceId("EG943322621391");
        session.setState(SessionState.FAILED);
        session.setVideoUri("video://x");
        when(repository.findByIdForUpdate("S-CD-1")).thenReturn(Optional.of(session));
        when(repository.findById("S-CD-1")).thenReturn(Optional.of(session));

        try {
            service.retryForOperations("S-CD-1");
        } catch (org.springframework.web.server.ResponseStatusException e) {
            assertFalse(String.valueOf(e.getReason()).contains("补货会话"),
                    "消费者会话不得被类型守卫拦截");
        } catch (Exception downstream) {
            // 守卫已通过，下游结算链失败可接受
        }
        verify(repository).clearFailReason("S-CD-1");
    }
}
