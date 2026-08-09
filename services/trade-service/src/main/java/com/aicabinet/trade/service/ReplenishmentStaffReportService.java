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

        Map<Long, StaffAgg> agg = new LinkedHashMap<>();
        for (ReplenishmentTask t : taskRepository.findByCreatedAtAfter(since)) {
            if (t.getAssigneeUserId() == null) {
                continue;
            }
            StaffAgg a = agg.computeIfAbsent(t.getAssigneeUserId(), k -> new StaffAgg());
            a.totalTasks++;
            if ("COMPLETED".equalsIgnoreCase(t.getStatus())) {
                a.completedTasks++;
                if (t.getCompletedAt() != null) {
                    a.durations.add(Duration.between(startTime(t), t.getCompletedAt()).toMinutes());
                }
            }
        }
        // 窗口外创建、窗口内完成的任务计入完成量（补全边界）
        for (ReplenishmentTask t : taskRepository.findByCompletedAtAfter(since)) {
            if (t.getAssigneeUserId() == null || !"COMPLETED".equalsIgnoreCase(t.getStatus())) {
                continue;
            }
            if (t.getCreatedAt() != null && t.getCreatedAt().isBefore(since)) {
                StaffAgg a = agg.computeIfAbsent(t.getAssigneeUserId(), k -> new StaffAgg());
                a.totalTasks++;
                a.completedTasks++;
                if (t.getCompletedAt() != null) {
                    a.durations.add(Duration.between(startTime(t), t.getCompletedAt()).toMinutes());
                }
            }
        }

        List<ReplenishmentStaffRowDto> out = new ArrayList<>();
        for (Map.Entry<Long, StaffAgg> e : agg.entrySet()) {
            StaffAgg a = e.getValue();
            long open = Math.max(0, a.totalTasks - a.completedTasks);
            double completionRate = a.totalTasks > 0 ? (double) a.completedTasks / a.totalTasks : 0.0;
            Double avgMinutes = a.durations.isEmpty()
                    ? null
                    : a.durations.stream().mapToDouble(Long::doubleValue).average().orElse(0.0);
            double avgDaily = a.totalTasks / (double) window;
            UserInfo user = userInfoRepository.findById(e.getKey()).orElse(null);
            out.add(new ReplenishmentStaffRowDto(
                    e.getKey(),
                    user != null ? user.getName() : null,
                    user != null ? user.getPhoneNumber() : null,
                    a.totalTasks,
                    a.completedTasks,
                    completionRate,
                    avgMinutes,
                    open,
                    Math.round(avgDaily * 100.0) / 100.0
            ));
        }
        out.sort(Comparator.comparingLong(ReplenishmentStaffRowDto::totalTasks).reversed());
        return out;
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
