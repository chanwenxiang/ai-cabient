package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ReplenishmentStaffRowDto;
import com.aicabinet.trade.domain.ReplenishmentTask;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.ReplenishmentTaskMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 补货员效率报表：任务量 / 完成率 / 平均耗时 / 日均任务。 */
@Service
public class ReplenishmentStaffReportService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ReplenishmentTaskMapper taskRepository;
    private final UserInfoMapper userInfoRepository;

    public ReplenishmentStaffReportService(ReplenishmentTaskMapper taskRepository,
                                           UserInfoMapper userInfoRepository) {
        this.taskRepository = taskRepository;
        this.userInfoRepository = userInfoRepository;
    }

    @Transactional(readOnly = true)
    public List<ReplenishmentStaffRowDto> report(int days) {
        int window = Math.min(Math.max(days, 7), 90);
        Instant since = LocalDate.now(ZONE).minusDays(window - 1L).atStartOfDay(ZONE).toInstant();
        Map<Long, StaffAgg> agg = aggregateStaffTasks(since);
        List<ReplenishmentStaffRowDto> out = new ArrayList<>();
        for (Map.Entry<Long, StaffAgg> e : agg.entrySet()) {
            out.add(toStaffRow(e.getKey(), e.getValue(), window));
        }
        out.sort(Comparator.comparingLong(ReplenishmentStaffRowDto::totalTasks).reversed());
        return out;
    }

    private Map<Long, StaffAgg> aggregateStaffTasks(Instant since) {
        Map<Long, StaffAgg> agg = new LinkedHashMap<>();
        for (ReplenishmentTask t : taskRepository.findByCreatedAtAfter(since)) {
            if (t.getAssigneeUserId() == null) {
                continue;
            }
            recordTaskCreatedInWindow(agg.computeIfAbsent(t.getAssigneeUserId(), k -> new StaffAgg()), t);
        }
        // 窗口外创建、窗口内完成的任务计入完成量（补全边界）
        for (ReplenishmentTask t : taskRepository.findByCompletedAtAfter(since)) {
            if (t.getAssigneeUserId() == null || !"COMPLETED".equalsIgnoreCase(t.getStatus())) {
                continue;
            }
            if (t.getCreatedAt() != null && t.getCreatedAt().isBefore(since)) {
                recordBoundaryCompleted(agg.computeIfAbsent(t.getAssigneeUserId(), k -> new StaffAgg()), t);
            }
        }
        return agg;
    }

    private static void recordTaskCreatedInWindow(StaffAgg agg, ReplenishmentTask task) {
        agg.totalTasks++;
        if ("COMPLETED".equalsIgnoreCase(task.getStatus())) {
            agg.completedTasks++;
            if (task.getCompletedAt() != null) {
                agg.durations.add(Duration.between(startTime(task), task.getCompletedAt()).toMinutes());
            }
        }
    }

    private static void recordBoundaryCompleted(StaffAgg agg, ReplenishmentTask task) {
        agg.totalTasks++;
        agg.completedTasks++;
        if (task.getCompletedAt() != null) {
            agg.durations.add(Duration.between(startTime(task), task.getCompletedAt()).toMinutes());
        }
    }

    private ReplenishmentStaffRowDto toStaffRow(Long userId, StaffAgg agg, int window) {
        long open = Math.max(0, agg.totalTasks - agg.completedTasks);
        double completionRate = agg.totalTasks > 0 ? (double) agg.completedTasks / agg.totalTasks : 0.0;
        Double avgMinutes = agg.durations.isEmpty()
                ? null
                : agg.durations.stream().mapToDouble(Long::doubleValue).average().orElse(0.0);
        double avgDaily = agg.totalTasks / (double) window;
        UserInfo user = userInfoRepository.findById(userId).orElse(null);
        return new ReplenishmentStaffRowDto(
                userId,
                user != null ? user.getName() : null,
                user != null ? user.getPhoneNumber() : null,
                agg.totalTasks,
                agg.completedTasks,
                completionRate,
                avgMinutes,
                open,
                Math.round(avgDaily * 100.0) / 100.0
        );
    }

    private static Instant startTime(ReplenishmentTask t) {
        return t.getCheckInAt() != null ? t.getCheckInAt() : t.getCreatedAt();
    }

    private static final class StaffAgg {
        long totalTasks;
        long completedTasks;
        final List<Long> durations = new ArrayList<>();
    }
}
