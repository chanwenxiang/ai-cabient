package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplenishmentStaffRowDto;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReplenishmentStaffReportServiceTest {

    @Mock private ReplenishmentTaskMapper taskRepository;
    @Mock private UserInfoMapper userInfoRepository;

    private static ReplenishmentTask task(Long assignee, String status, Instant createdAt,
                                          Instant checkInAt, Instant completedAt) {
        ReplenishmentTask t = new ReplenishmentTask();
        t.setAssigneeUserId(assignee);
        t.setStatus(status);
        t.setCreatedAt(createdAt);
        t.setCheckInAt(checkInAt);
        t.setCompletedAt(completedAt);
        return t;
    }

    @Test
    void report_shouldAggregateCompletionRateAndAvgDuration() {
        ReplenishmentStaffReportService service =
                new ReplenishmentStaffReportService(taskRepository, userInfoRepository);
        Instant now = Instant.now();
        Instant checkIn = now.minus(40, ChronoUnit.MINUTES);
        Instant completed = now.minus(10, ChronoUnit.MINUTES);

        when(taskRepository.findByCreatedAtAfter(any())).thenReturn(List.of(
                task(7L, "COMPLETED", now.minus(2, ChronoUnit.HOURS), checkIn, completed),
                task(7L, "COMPLETED", now.minus(1, ChronoUnit.HOURS), checkIn.minus(5, ChronoUnit.MINUTES), completed.minus(5, ChronoUnit.MINUTES)),
                task(7L, "PENDING", now, null, null),
                task(8L, "PENDING", now, null, null)
        ));
        when(taskRepository.findByCompletedAtAfter(any())).thenReturn(List.of());

        List<ReplenishmentStaffRowDto> rows = service.report(30);

        assertEquals(2, rows.size());
        ReplenishmentStaffRowDto staff7 = rows.stream()
                .filter(r -> r.userId() == 7L).findFirst().orElseThrow();
        assertEquals(3, staff7.totalTasks());
        assertEquals(2, staff7.completedTasks());
        assertEquals(1, staff7.openTasks());
        assertEquals(2.0 / 3.0, staff7.completionRate(), 0.001);
        assertEquals(30.0, staff7.avgDurationMinutes(), 0.001); // (40-10 + 40-10) / 2 = 30
        ReplenishmentStaffRowDto staff8 = rows.stream()
                .filter(r -> r.userId() == 8L).findFirst().orElseThrow();
        assertEquals(0.0, staff8.completionRate(), 0.001);
        assertNull(staff8.avgDurationMinutes());
    }
}
