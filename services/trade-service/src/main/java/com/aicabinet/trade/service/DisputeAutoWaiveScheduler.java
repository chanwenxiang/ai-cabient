package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ResolveDisputeResultDto;
import com.aicabinet.trade.config.DisputeAutoWaiveProperties;
import com.aicabinet.trade.domain.DisputeTicket;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.DisputeTicketMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * P3-4 争议超时自动免单（docs/P3_4_DISPUTE_AUTO_WAIVE_DESIGN.md）。
 *
 * <p>门控（设计稿 §3，全部满足才免单）：OPEN + 超时来源（reason 前缀「识别超时」）+
 * 未认领（assignee 为空）+ 创建早于阈值（默认 72h）；护栏：总开关默认 OFF、
 * 单轮上限、单用户滚动窗口上限（历史按 operator_note='AUTO_WAIVE' 统计）。
 * 资金事实：超时单从未扣款且预授权已释放（C09），WAIVE 对未扣款单返 0——零资金移动纯结案。</p>
 */
@Service
public class DisputeAutoWaiveScheduler {
    private static final Logger log = LoggerFactory.getLogger(DisputeAutoWaiveScheduler.class);
    private static final String TASK_KEY = "dispute-auto-waive";
    private static final String AUTO_WAIVE_NOTE = "AUTO_WAIVE";
    private static final int SCAN_BATCH = 200;

    private final DisputeAutoWaiveProperties properties;
    private final SystemConfigService systemConfigService;
    private final DisputeTicketMapper disputeRepository;
    private final DisputeService disputeService;
    private final ShoppingSessionMapper sessionRepository;
    private final JdbcTemplate jdbcTemplate;
    private final OpsAlertDispatcher alertDispatcher;
    private final ScheduledTaskService taskService;

    public DisputeAutoWaiveScheduler(DisputeAutoWaiveProperties properties,
                                     SystemConfigService systemConfigService,
                                     DisputeTicketMapper disputeRepository,
                                     DisputeService disputeService,
                                     ShoppingSessionMapper sessionRepository,
                                     JdbcTemplate jdbcTemplate,
                                     OpsAlertDispatcher alertDispatcher,
                                     ScheduledTaskService taskService) {
        this.properties = properties;
        this.systemConfigService = systemConfigService;
        this.disputeRepository = disputeRepository;
        this.disputeService = disputeService;
        this.sessionRepository = sessionRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.alertDispatcher = alertDispatcher;
        this.taskService = taskService;
    }

    @Scheduled(fixedRate = 900_000)
    public void autoWaive() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(TASK_KEY, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "争议自动免单未启用";
        try {
            if (!properties.enabled()) {
                return;
            }
            summary = runOnce();
        } catch (Exception e) {
            failed = true;
            taskService.finish(TASK_KEY, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(TASK_KEY, "SUCCESS", summary, start);
            }
        }
    }

    /** 单轮扫描：返回可读 summary（供 scheduled_task.last_run_at 与飞书告警）。 */
    private String runOnce() {
        // P3-4 调参后台可调（系统配置页，即时生效）；properties 仅作兜底默认
        int hours = systemConfigService.getInt(
                SystemConfigService.DISPUTE_AUTO_WAIVE_HOURS, properties.hours());
        int maxPerRound = Math.min(systemConfigService.getInt(
                SystemConfigService.DISPUTE_AUTO_WAIVE_MAX_PER_ROUND, properties.maxPerRound()), SCAN_BATCH);
        int perUserMax = systemConfigService.getInt(
                SystemConfigService.DISPUTE_AUTO_WAIVE_PER_USER_MAX, properties.perUserMax());
        int perUserWindowDays = systemConfigService.getInt(
                SystemConfigService.DISPUTE_AUTO_WAIVE_PER_USER_WINDOW_DAYS, properties.perUserWindowDays());
        Instant cutoff = Instant.now().minus(hours, ChronoUnit.HOURS);
        List<DisputeTicket> candidates =
                disputeRepository.findOpenTimeoutUnclaimedCreatedBefore(cutoff, SCAN_BATCH);
        if (candidates.isEmpty()) {
            return "候选 0 张";
        }
        int waived = 0;
        int skippedAbuse = 0;
        int failures = 0;
        Map<Long, Integer> perUserThisRound = new HashMap<>();
        for (DisputeTicket ticket : candidates) {
            if (waived >= maxPerRound) {
                break;
            }
            Long userId = resolveUserId(ticket);
            if (userId != null && autoWaiveCountInWindow(userId, perUserWindowDays)
                    + perUserThisRound.getOrDefault(userId, 0) >= perUserMax) {
                skippedAbuse++;
                continue;
            }
            try {
                ResolveDisputeResultDto result = disputeService.autoWaiveTicket(ticket.getTicketId());
                if (result != null) {
                    waived++;
                    if (userId != null) {
                        perUserThisRound.merge(userId, 1, Integer::sum);
                    }
                }
            } catch (Exception e) {
                failures++;
                log.error("争议自动免单失败 ticket={}", ticket.getTicketId(), e);
            }
        }
        String summary = "候选 " + candidates.size() + "，免单 " + waived
                + "，防薅跳过 " + skippedAbuse + "，失败 " + failures;
        if (waived > 0 || skippedAbuse > 0) {
            try {
                alertDispatcher.send("DISPUTE", "争议超时自动免单", summary);
            } catch (Exception e) {
                log.error("争议自动免单告警发送失败", e);
            }
        }
        return summary;
    }

    private Long resolveUserId(DisputeTicket ticket) {
        return sessionRepository.findById(ticket.getSessionId())
                .map(ShoppingSession::getUserId)
                .orElse(null);
    }

    /** 滚动窗口内该用户已被自动免单的历史张数（operator_note 标记）。 */
    private int autoWaiveCountInWindow(Long userId, int perUserWindowDays) {
        Instant since = Instant.now().minus(perUserWindowDays, ChronoUnit.DAYS);
        Integer count = jdbcTemplate.query(
                "SELECT COUNT(*) FROM dispute_ticket t "
                        + "JOIN shopping_session s ON s.session_id = t.session_id "
                        + "WHERE s.user_id = ? AND t.operator_note = ? AND t.status = 'RESOLVED' "
                        + "AND t.resolved_at > ?",
                rs -> rs.next() ? rs.getInt(1) : 0,
                userId, AUTO_WAIVE_NOTE, java.sql.Timestamp.from(since));
        return count != null ? count : 0;
    }
}
