package com.aicabinet.trade.service;

import com.aicabinet.trade.config.DisputeSlaProperties;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * H37：SLA webhook 发送失败时不得落 slaReminderAt/slaAlertedAt，
 * 本轮不标记、下轮扫描重试；发送成功（至少一个渠道）才落标记。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisputeSlaSchedulerAckTest {

    @Mock private DisputeTicketMapper disputeRepository;
    @Mock private DisputeSlaAlertService alertService;
    @Mock private ScheduledTaskService taskService;
    @Mock private SystemConfigService systemConfigService;

    private DisputeSlaScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DisputeSlaScheduler(
                new DisputeSlaProperties(48, 12, null, true),
                disputeRepository, alertService, taskService, systemConfigService);
        lenient().when(taskService.tryBegin(eq("dispute-sla"), anyLong())).thenReturn(true);
        lenient().when(systemConfigService.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
    }

    private DisputeTicket ticketNearDue() {
        DisputeTicket ticket = new DisputeTicket();
        ticket.setTicketId("100");
        ticket.setCreatedAt(Instant.now().minus(40, ChronoUnit.HOURS));
        // 4 小时后到期 → 落入 12h 提醒窗口，且尚未逾期
        ticket.setSlaDueAt(Instant.now().plus(4, ChronoUnit.HOURS));
        return ticket;
    }

    @Test
    void reminderSendFailed_slaReminderAtNotMarked() {
        DisputeTicket ticket = ticketNearDue();
        when(disputeRepository.findOpenNeedingSlaScan(500)).thenReturn(List.of(ticket));
        when(alertService.trySendReminder(ticket)).thenReturn(false);

        scheduler.checkDisputeSla();

        assertNull(ticket.getSlaReminderAt(), "发送失败不得标记已提醒");
        verify(disputeRepository, never()).save(ticket);
    }

    @Test
    void reminderSendSucceeded_slaReminderAtMarked() {
        DisputeTicket ticket = ticketNearDue();
        when(disputeRepository.findOpenNeedingSlaScan(500)).thenReturn(List.of(ticket));
        when(alertService.trySendReminder(ticket)).thenReturn(true);

        scheduler.checkDisputeSla();

        assertNotNull(ticket.getSlaReminderAt(), "发送成功才落提醒标记");
        verify(disputeRepository).save(ticket);
    }

    @Test
    void overdueSendFailed_slaAlertedAtNotMarked() {
        DisputeTicket ticket = ticketNearDue();
        ticket.setSlaReminderAt(Instant.now().minus(6, ChronoUnit.HOURS));
        // 已逾期
        ticket.setSlaDueAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(disputeRepository.findOpenNeedingSlaScan(500)).thenReturn(List.of(ticket));
        when(alertService.trySendOverdue(ticket)).thenReturn(false);

        scheduler.checkDisputeSla();

        assertNull(ticket.getSlaAlertedAt(), "发送失败不得标记已告警");
        verify(disputeRepository, never()).save(ticket);
    }
}
