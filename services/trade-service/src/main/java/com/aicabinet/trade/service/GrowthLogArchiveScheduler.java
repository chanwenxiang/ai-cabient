package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Set;

/** 增长模块日志归档：清理通知日志；积分流水为账本组成部分，不在此删除。 */
@Service
public class GrowthLogArchiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(GrowthLogArchiveScheduler.class);
    private static final int BATCH = 500;
    /** 仅允许归档已知日志表，禁止把表名交给 JDBC 拼接用户输入。 */
    private static final Set<String> ALLOWED_TABLES = Set.of("notification_log", "member_points_log");

    private final JdbcTemplate jdbcTemplate;
    private final SystemConfigService systemConfigService;

    @Autowired
    private ScheduledTaskService taskService;

    public GrowthLogArchiveScheduler(JdbcTemplate jdbcTemplate,
                                     SystemConfigService systemConfigService) {
        this.jdbcTemplate = jdbcTemplate;
        this.systemConfigService = systemConfigService;
    }

    @Scheduled(fixedRate = 24 * 3_600_000L)
    @Transactional
    public void archive() {
        long start = System.nanoTime();
        if (!taskService.tryBegin("growth-log-archive", 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无归档删除";
        try {
            int notifyMonths = systemConfigService.getInt("ops.log_retention.notify_months", 6);
            int deleted = 0;
            if (notifyMonths > 0) {
                deleted += deleteInBatches("notification_log",
                        Instant.now().minus(notifyMonths, ChronoUnit.MONTHS), false);
            }
            // member_points_log 与 member.available_points 对账依赖完整流水，禁止 DELETE
            summary = deleted <= 0 ? "本次无归档删除" : "归档删除 " + deleted + " 条";
            if (deleted > 0) {
                log.info("growth log archive deleted={} notifyMonths={}",
                        deleted, notifyMonths);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish("growth-log-archive", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("growth-log-archive", "SUCCESS", summary, start);
            }
        }
    }

    private int deleteInBatches(String table, Instant cutoff, boolean requireExpiredAt) {
        if (!ALLOWED_TABLES.contains(table)) {
            throw new IllegalArgumentException("unsupported archive table: " + table);
        }
        String extra = requireExpiredAt ? " AND expired_at IS NOT NULL" : "";
        String sql = "DELETE FROM " + table
                + " WHERE id IN (SELECT id FROM " + table
                + " WHERE created_at < ?" + extra + " LIMIT " + BATCH + ")";
        Timestamp cutoffTs = Timestamp.from(cutoff);
        int total = 0;
        int n;
        do {
            n = jdbcTemplate.update(sql, cutoffTs);
            total += n;
        } while (n >= BATCH);
        return total;
    }
}
