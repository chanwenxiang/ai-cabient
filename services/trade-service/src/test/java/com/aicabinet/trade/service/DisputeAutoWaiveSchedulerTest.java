package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ResolveDisputeResultDto;
import com.aicabinet.trade.config.DisputeAutoWaiveProperties;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-4 门控矩阵：未启用不扫、超时+未认领才免单、防薅单用户上限、单轮上限、失败不炸轮。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeAutoWaiveSchedulerTest {

    @Mock private DisputeTicketMapper disputeRepository;
    @Mock private DisputeService disputeService;
    @Mock private ShoppingSessionMapper sessionRepository;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private OpsAlertDispatcher alertDispatcher;
    @Mock private ScheduledTaskService taskService;
    @Mock private SystemConfigService systemConfigService;

    private DisputeAutoWaiveScheduler scheduler;

    @BeforeEach
    void setUp() {
        lenient().when(taskService.tryBegin(eq("dispute-auto-waive"), anyLong())).thenReturn(true);
        lenient().when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), any(), any(), any()))
                .thenReturn(0);
        // 配置缺省走 properties 兜底
        lenient().when(systemConfigService.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(inv -> inv.getArgument(1));
        scheduler = new DisputeAutoWaiveScheduler(properties(false), systemConfigService, disputeRepository,
                disputeService, sessionRepository, jdbcTemplate, alertDispatcher, taskService);
    }

    private static DisputeAutoWaiveProperties properties(boolean enabled) {
        return new DisputeAutoWaiveProperties(enabled, 72, 50, 3, 7);
    }

    private static DisputeTicket ticket(String id, String sessionId) {
        DisputeTicket t = new DisputeTicket();
        t.setTicketId(id);
        t.setSessionId(sessionId);
        t.setStatus("OPEN");
        t.setReason("识别超时，已转人工审核，本次暂未扣款");
        t.setCreatedAt(Instant.now().minusSeconds(3600));
        return t;
    }

    private void stubSession(String sessionId, Long userId) {
        ShoppingSession s = new ShoppingSession();
        s.setSessionId(sessionId);
        s.setUserId(userId);
        lenient().when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(s));
    }

    private void enable() {
        scheduler = new DisputeAutoWaiveScheduler(properties(true), systemConfigService, disputeRepository,
                disputeService, sessionRepository, jdbcTemplate, alertDispatcher, taskService);
    }

    @Test
    void disabled_doesNotScan() {
        scheduler.autoWaive();
        verify(disputeRepository, never()).findOpenTimeoutUnclaimedCreatedBefore(any(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void enabled_waivesEligibleAndAlerts() {
        enable();
        stubSession("S-1", 100L);
        when(disputeRepository.findOpenTimeoutUnclaimedCreatedBefore(any(), eq(200)))
                .thenReturn(List.of(ticket("T-1", "S-1")));
        when(disputeService.autoWaiveTicket("T-1")).thenReturn(
                new ResolveDisputeResultDto(null, "WAIVE", 0, 0, 0, "已免单，无需扣款"));

        scheduler.autoWaive();

        verify(disputeService).autoWaiveTicket("T-1");
        verify(alertDispatcher).send(eq("DISPUTE"), anyString(), anyString());
    }

    @Test
    void perUserCap_skipsBeyondLimit() {
        enable();
        for (int i = 1; i <= 4; i++) {
            stubSession("S-" + i, 100L);
        }
        when(disputeRepository.findOpenTimeoutUnclaimedCreatedBefore(any(), eq(200)))
                .thenReturn(List.of(ticket("T-1", "S-1"), ticket("T-2", "S-2"),
                        ticket("T-3", "S-3"), ticket("T-4", "S-4")));
        when(disputeService.autoWaiveTicket(anyString())).thenReturn(
                new ResolveDisputeResultDto(null, "WAIVE", 0, 0, 0, "已免单，无需扣款"));
        // 历史已 1 张 ⇒ 本轮只允许再免 2 张（上限 3）
        when(jdbcTemplate.query(anyString(), any(ResultSetExtractor.class), eq(100L), anyString(), any()))
                .thenReturn(1);

        scheduler.autoWaive();

        verify(disputeService, times(2)).autoWaiveTicket(anyString());
        verify(disputeService, never()).autoWaiveTicket(eq("T-4"));
        verify(alertDispatcher).send(eq("DISPUTE"), anyString(), anyString());
    }

    @Test
    void configOverridesProperties_horusThresholdFromSystemConfig() {
        enable();
        stubSession("S-1", 100L);
        when(disputeRepository.findOpenTimeoutUnclaimedCreatedBefore(any(), eq(200)))
                .thenReturn(List.of());
        // 后台把阈值改成 999999 小时 ⇒ 4 天前的单不再够格（证明读的是配置而非 properties）
        when(systemConfigService.getInt(
                eq(SystemConfigService.DISPUTE_AUTO_WAIVE_HOURS), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(999999);

        scheduler.autoWaive();

        verify(disputeRepository).findOpenTimeoutUnclaimedCreatedBefore(
                org.mockito.ArgumentMatchers.argThat(c -> c.isBefore(Instant.now().minus(365, java.time.temporal.ChronoUnit.DAYS))),
                org.mockito.ArgumentMatchers.eq(200));
        verify(alertDispatcher, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void autoWaiveTicketReturnsNullOnResolved_doesNotCountOrAlert() {
        enable();
        stubSession("S-1", 100L);
        when(disputeRepository.findOpenTimeoutUnclaimedCreatedBefore(any(), eq(200)))
                .thenReturn(List.of(ticket("T-1", "S-1")));
        when(disputeService.autoWaiveTicket("T-1")).thenReturn(null);

        scheduler.autoWaive();

        verify(alertDispatcher, never()).send(anyString(), anyString(), anyString());
    }
}
