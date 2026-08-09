package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** 增长模块日志归档：按保留月数分批清理通知/积分日志，避免表无限增长。 */
@Service
public class GrowthLogArchiveScheduler {

    private static final Logger log = LoggerFactory.getLogger(GrowthLogArchiveScheduler.class);
    private static final int BATCH = 500;

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
        try {
            int notifyMonths = systemConfigService.getInt("ops.log_retention.notify_months", 6);
            int pointsMonths = systemConfigService.getInt("ops.log_retention.points_months", 12);
            int deleted = 0;
            if (notifyMonths > 0) {
                deleted += deleteInBatches("notification_log",
                        Instant.now().minus(notifyMonths, ChronoUnit.MONTHS), null);
            }
            if (pointsMonths > 0) {
                deleted += deleteInBatches("member_points_log",
                        Instant.now().minus(pointsMonths, ChronoUnit.MONTHS), "expired_at IS NOT NULL");
            }
            if (deleted > 0) {
                log.info("growth log archive deleted={} notifyMonths={} pointsMonths={}",
                        deleted, notifyMonths, pointsMonths);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish("growth-log-archive", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("growth-log-archive", "SUCCESS", null, start);
            }
        }
    }

    private int deleteInBatches(String table, Instant cutoff, String extraWhere) {
        String where = "created_at < '" + cutoff + "'"
                + (extraWhere != null ? " AND " + extraWhere : "");
        int total = 0;
        int n;
        do {
            n = jdbcTemplate.update("DELETE FROM " + table
                    + " WHERE id IN (SELECT id FROM " + table + " WHERE " + where + " LIMIT " + BATCH + ")");
            total += n;
        } while (n >= BATCH);
        return total;
    }
}
