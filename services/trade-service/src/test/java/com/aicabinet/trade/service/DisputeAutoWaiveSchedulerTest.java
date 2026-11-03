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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
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
        // 🔴 @Mock 的 getBoolean 默认返回 **false**：总开关改成读 SystemConfig 后，
        //    不补这条兜底桩会让「env=true 应扫」的用例集体变红（且是静默不扫，不是断言报错）。
        lenient().when(systemConfigService.getBoolean(anyString(), anyBoolean()))
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

    /** 参数配置是权威：库里写了 false，env 兜底 true 也必须让位（运营在后台关掉的开关不能被 env 悄悄翻回来）。 */
    @Test
    void configFalse_overridesEnvTrue_andDoesNotScan() {
        enable();
        when(systemConfigService.getBoolean(
                eq(SystemConfigService.DISPUTE_AUTO_WAIVE_ENABLED), anyBoolean())).thenReturn(false);

        scheduler.autoWaive();

        verify(disputeRepository, never())
                .findOpenTimeoutUnclaimedCreatedBefore(any(), org.mockito.ArgumentMatchers.anyInt());
        // 台账要能区分「参数配置关的」与「env 兜底关的」，否则运营看不出是谁关的
        verify(taskService).finish(eq("dispute-auto-waive"), eq("SUCCESS"),
                argThat(msg -> msg != null && msg.contains("参数配置")), anyLong());
    }

    /** 反向：库里 true 可以把 env=false 抬起来（新库还没配 env 时也能在后台直接开）。 */
    @Test
    void configTrue_enablesEvenWhenEnvFalse() {
        when(systemConfigService.getBoolean(
                eq(SystemConfigService.DISPUTE_AUTO_WAIVE_ENABLED), anyBoolean())).thenReturn(true);
        stubSession("S-CFG", 300L);
        when(disputeRepository.findOpenTimeoutUnclaimedCreatedBefore(any(), eq(200)))
                .thenReturn(List.of(ticket("T-CFG", "S-CFG")));

        scheduler.autoWaive();

        verify(disputeRepository, times(1)).findOpenTimeoutUnclaimedCreatedBefore(any(), eq(200));
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
