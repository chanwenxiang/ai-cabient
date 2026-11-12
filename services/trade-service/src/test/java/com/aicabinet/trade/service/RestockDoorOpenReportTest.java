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
 * 将邑开门回执（WS sendOpenStockDoorSure/openDoorSure → /internal/v1/jiangyi/restock-door-opened）：
 * 2026-10-30 把「是否放行」的判定从网关 tracker 移到这里——tracker 登记会被 watchdog
 * 15s open-failed 先清掉，回执稍慢即丢。
 *
 * <p>2026-11-12 真机事故（会话 1794452035347297629828）放开消费会话：弱网下设备 HTTP
 * uploadLockState 迟到 2m13s，10s OPEN_TIMEOUT 清扫误杀真开门；openDoorSure 回执
 * （迟到 14.3s）是唯一能在清扫前到达的设备响应信号。锁四件事：补货/消费 OPENING 回执
 * 推进到 SHOPPING（不结算）；重复回执幂等；终态会话不复活；设备不符/无此会话一律
 * false 且不进锁与门事件路径。</p>
 */
@ExtendWith(MockitoExtension.class)
class RestockDoorOpenReportTest {

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
        session.setSessionId("S-RO-1");
        session.setDeviceId("EG943322621391");
        session.setReplenishmentTaskId(5L);
        session.setUserId(100L);
        session.setState(state);
        return session;
    }

    private ShoppingSession consumerSession() {
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("S-RO-2");
        session.setDeviceId("EG943322621391");
        session.setUserId(200L);
        session.setState(SessionState.OPENING);
        return session;
    }

    @Test
    void openingRestockSession_advancesToShopping() {
        ShoppingSession session = restockSession(SessionState.OPENING);
        when(repository.findById("S-RO-1")).thenReturn(Optional.of(session));
        when(repository.findByIdForUpdate("S-RO-1")).thenReturn(Optional.of(session));

        assertTrue(doorService.applyRestockDoorOpened("EG943322621391", "S-RO-1"));
        assertEquals(SessionState.SHOPPING, session.getState());
        assertNotNull(session.getOpenTime());
        verify(settlementService, never()).settle(any());
    }

    @Test
    void repeatedOpenAck_isIdempotent() {
        ShoppingSession session = restockSession(SessionState.SHOPPING);
        when(repository.findById("S-RO-1")).thenReturn(Optional.of(session));
        when(repository.findByIdForUpdate("S-RO-1")).thenReturn(Optional.of(session));

        assertTrue(doorService.applyRestockDoorOpened("EG943322621391", "S-RO-1"));
        assertEquals(SessionState.SHOPPING, session.getState());
    }

    @Test
    void consumerOpeningSession_rescuedByAck() {
        // 2026-11-12 真机事故救援路径：消费 OPENING 会话收到迟到 openDoorSure 回执（14.3s，
        // HTTP lock 上报还在路上）→ 与补货同语义推进 SHOPPING，清扫不再误杀真开门
        ShoppingSession session = consumerSession();
        when(repository.findById("S-RO-2")).thenReturn(Optional.of(session));
        when(repository.findByIdForUpdate("S-RO-2")).thenReturn(Optional.of(session));

        assertTrue(doorService.applyRestockDoorOpened("EG943322621391", "S-RO-2"));
        assertEquals(SessionState.SHOPPING, session.getState());
        assertNotNull(session.getOpenTime());
        verify(settlementService, never()).settle(any());
    }

    @Test
    void terminalSession_isNotRevived() {
        // 已被超时清扫杀掉的会话：迟到回执不复活（兜账走迟到识别上报的运维异常路径）
        ShoppingSession session = consumerSession();
        session.setState(SessionState.CANCELLED);
        when(repository.findById("S-RO-2")).thenReturn(Optional.of(session));

        assertFalse(doorService.applyRestockDoorOpened("EG943322621391", "S-RO-2"));
        assertEquals(SessionState.CANCELLED, session.getState());
        verify(repository, never()).findByIdForUpdate(anyString());
    }

    @Test
    void deviceMismatch_isIgnored() {
        when(repository.findById("S-RO-1")).thenReturn(Optional.of(restockSession(SessionState.OPENING)));

        assertFalse(doorService.applyRestockDoorOpened("EG-OTHER", "S-RO-1"));
        verify(repository, never()).findByIdForUpdate(anyString());
    }

    @Test
    void unknownSession_isIgnored() {
        when(repository.findById("S-RO-9")).thenReturn(Optional.empty());

        assertFalse(doorService.applyRestockDoorOpened("EG943322621391", "S-RO-9"));
    }

    @Test
    void blankArgs_returnFalseWithoutQuery() {
        assertFalse(doorService.applyRestockDoorOpened(" ", "S-RO-1"));
        assertFalse(doorService.applyRestockDoorOpened("EG943322621391", " "));
        assertFalse(doorService.applyRestockDoorOpened(null, "S-RO-1"));
        assertFalse(doorService.applyRestockDoorOpened("EG943322621391", null));
        verify(repository, never()).findById(anyString());
    }
}
