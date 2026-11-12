package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.aicabinet.trade.support.ScheduleZones;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;

/** 增长模块日志归档：清理通知日志与设备温度遥测（R22-9 保留窗）；积分流水为账本组成部分，不在此删除。 */
@Service
public class GrowthLogArchiveScheduler {
    private static final String GROWTH_LOG_ARCHIVE = "growth-log-archive";


    private static final Logger log = LoggerFactory.getLogger(GrowthLogArchiveScheduler.class);
    private static final int BATCH = 500;
    /**
     * 单次运行最多删多少批（500 × 200 = 10 万行）。
     *
     * <p>P3-r19-10（2026-11-12）：首次运行可能积压 6 个月日志，原实现「一个事务删到底」——
     * 长事务持锁、WAL 膨胀，还可能与业务写入互相拖死。上限让单次可控，剩余量下次继续。
     */
    private static final int MAX_BATCHES_PER_RUN = 200;
    /** 批间短暂让出，避免连续 DELETE 把 IO / autovacuum 顶满。 */
    private static final long BATCH_PAUSE_MS = 50L;

    private final JdbcTemplate jdbcTemplate;
    private final SystemConfigService systemConfigService;
    private final ScheduledTaskService taskService;

    public GrowthLogArchiveScheduler(JdbcTemplate jdbcTemplate,
                                     SystemConfigService systemConfigService,
                                     ScheduledTaskService taskService) {
        this.jdbcTemplate = jdbcTemplate;
        this.systemConfigService = systemConfigService;
        this.taskService = taskService;
    }

    @Scheduled(fixedRate = 24 * 3_600_000L)
    // P3-r19-10：**故意不加方法级 @Transactional** —— 每批 DELETE 各自提交，
    // 中途失败只损失「本轮剩余批次」（归档删除幂等，下次继续），不会留下长事务。
    public void archive() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(GROWTH_LOG_ARCHIVE, 600)) {
            return;
        }
        boolean finished = false;
        String summary = "本次无归档删除";
        try {
            int notifyMonths = systemConfigService.getInt("ops.log_retention.notify_months", 6);
            int deleted = 0;
            if (notifyMonths > 0) {
                // 注意：Instant 只支持到 DAYS，minus(MONTHS) 必抛 UnsupportedTemporalTypeException
                // （该任务曾因此 100% 失败且无人发现）。月份运算必须在带日历的 ZonedDateTime 上做，
                // 且按业务时区解释，否则月末/月初会错一天。
                Instant cutoff = ZonedDateTime.now(ScheduleZones.ZONE)
                        .minusMonths(notifyMonths)
                        .toInstant();
                deleted += deleteInBatches("notification_log", cutoff, false);
            }
            // R22-9（四端审计 2026-11-12）：device_temperature_reading 无界增长——每次心跳带
            // 温度即存一行（实测 4 机 11 天 55,287 行 ≈ 1,250 行/机/天），读取只取近窗，
            // 无任何保留策略，扩容/备份/vacuum 窗口都会被拖累。默认保留 90 天（0=关闭），
            // 分批删复用 notification_log 同款范式；同批总量封顶逻辑共用。
            int tempDays = systemConfigService.getInt("ops.log_retention.temperature_days", 90);
            if (tempDays > 0) {
                Instant tempCutoff = ZonedDateTime.now(ScheduleZones.ZONE)
                        .minusDays(tempDays)
                        .toInstant();
                deleted += deleteInBatches("device_temperature_reading", tempCutoff, false);
            }
            // member_points_log 与 member.available_points 对账依赖完整流水，禁止 DELETE
            summary = deleted <= 0 ? "本次无归档删除" : "归档删除 " + deleted + " 条";
            if (deleted > 0) {
                log.info("growth log archive deleted={} notifyMonths={}",
                        deleted, notifyMonths);
            }
        } catch (Exception e) {
            finished = true;
            taskService.finish(GROWTH_LOG_ARCHIVE, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!finished) {
                taskService.finish(GROWTH_LOG_ARCHIVE, "SUCCESS", summary, start);
            }
        }
    }

    private int deleteInBatches(String table, Instant cutoff, boolean requireExpiredAt) {
        // 表名与时间列都写死在白名单 case 内，不来自外部输入，避免动态拼接触发 SQLi 热点。
        // R22-5（四端审计 2026-11-12）：member_points_log 分支已删——该表与
        // member.available_points 对账依赖完整流水、注释明令禁止 DELETE，而本方法
        // 却留着一条可被顺手接上的删除分支（上了膛的枪）。删除通道（AdminDataManageService）
        // 已同步把 member_points_log 划入资金账本保护集；本任务只清 notification_log 与温度遥测。
        String sql = switch (table) {
            case "notification_log" -> requireExpiredAt
                    ? """
                    DELETE FROM notification_log
                    WHERE id IN (
                      SELECT id FROM notification_log
                      WHERE created_at < ? AND expired_at IS NOT NULL
                      LIMIT %d
                    )
                    """.formatted(BATCH)
                    : """
                    DELETE FROM notification_log
                    WHERE id IN (
                      SELECT id FROM notification_log
                      WHERE created_at < ?
                      LIMIT %d
                    )
                    """.formatted(BATCH);
            case "device_temperature_reading" -> """
                    DELETE FROM device_temperature_reading
                    WHERE reading_id IN (
                      SELECT reading_id FROM device_temperature_reading
                      WHERE reported_at < ?
                      LIMIT %d
                    )
                    """.formatted(BATCH);
            default -> throw new IllegalArgumentException("unsupported archive table: " + table);
        };
        Timestamp cutoffTs = Timestamp.from(cutoff);
        int total = 0;
        int batches = 0;
        int n;
        do {
            n = jdbcTemplate.update(sql, cutoffTs);
            total += n;
            batches++;
            if (n < BATCH) {
                break;
            }
            // 单次运行总量封顶：积压很多时留给下一轮，避免一夜之间长占 IO
            if (batches >= MAX_BATCHES_PER_RUN) {
                log.warn("growth log archive hit per-run cap table={} batches={} total={}", table, batches, total);
                break;
            }
            try {
                Thread.sleep(BATCH_PAUSE_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        } while (true);
        return total;
    }
}
