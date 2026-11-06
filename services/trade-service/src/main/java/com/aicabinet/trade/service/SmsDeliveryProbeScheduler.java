package com.aicabinet.trade.service;

import com.aicabinet.trade.config.SmsDeliveryProbeProperties;
import com.aicabinet.trade.sms.AliyunSmsSender;
import com.aicabinet.trade.sms.AliyunSmsSender.SmsReceipt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 短信到达率巡检（C 档修订形态）。按 (号码, 发送日) 拉阿里云回执，聚合成送达/发送中/失败，
 * 失败占比超阈值时走 ops-alert 群 + 异常台账留痕。
 *
 * <p>两把闸的分工与 P3-4 同款：{@code sms.delivery_probe.enabled}（参数配置，优先于 env）
 * 决定「跑不跑查询」；「系统 → 定时任务」里 {@code sms-delivery-probe} 的开关决定「整个任务是否执行」
 * （关掉后连执行记录都没有）。</p>
 *
 * <p>🔴 为什么不是逐行回填：阿里云 {@code QuerySendDetails} 返回体没有 BizId，
 * 同号同日多条发送对不回具体日志行；且回执只保留最近几天 ⇒ 必须近实时轮询，事后补扫来不及。
 * 默认 OFF：当前生产无真实发送量，空跑一条外呼链路没有意义（fail-closed）。</p>
 */
@Service
public class SmsDeliveryProbeScheduler {

    private static final Logger log = LoggerFactory.getLogger(SmsDeliveryProbeScheduler.class);
    static final String TASK_KEY = "sms-delivery-probe";
    static final String ALERT_TYPE = "SMS_DELIVERY_FAILURE_HIGH";
    /** 系统级异常没有设备/订单引用 ⇒ 去重键落到 GLOBAL（同 ScheduledTaskStaleMonitor）。 */
    private static final String GLOBAL_BUSINESS_KEY = "GLOBAL";
    /** 单轮最多查多少个 (号码,日) 组合：阿里云查询也计入调用配额，必须有上界。 */
    static final int MAX_PAIRS_PER_ROUND = 60;
    private static final Duration REALERT_INTERVAL = Duration.ofHours(6);

    private static final String SQL_CAPTCHA_TARGETS = """
            select distinct phone_number,
                   to_char(created_at at time zone 'Asia/Shanghai', 'YYYYMMDD') as send_date
              from sms_verification_code
             where created_at > now() - make_interval(days => ?)
             order by 1, 2
             limit 80
            """;

    private static final String SQL_NOTIFY_TARGETS = """
            select distinct u.phone_number,
                   to_char(n.created_at at time zone 'Asia/Shanghai', 'YYYYMMDD') as send_date
              from notification_log n
              join user_info u on u.user_id = n.user_id
             where n.channel = 'SMS'
               and n.created_at > now() - make_interval(days => ?)
             order by 1, 2
             limit 80
            """;

    private final AliyunSmsSender aliyunSmsSender;
    private final JdbcTemplate jdbcTemplate;
    private final SystemConfigService systemConfigService;
    private final OpsAlertDispatcher alertDispatcher;
    private final OpsExceptionService exceptionService;
    private final ScheduledTaskService taskService;
    private final SmsDeliveryProbeProperties properties;

    private String lastAlertFingerprint;
    private Instant lastAlertAt;

    public SmsDeliveryProbeScheduler(AliyunSmsSender aliyunSmsSender,
                                     JdbcTemplate jdbcTemplate,
                                     SystemConfigService systemConfigService,
                                     OpsAlertDispatcher alertDispatcher,
                                     OpsExceptionService exceptionService,
                                     ScheduledTaskService taskService,
                                     SmsDeliveryProbeProperties properties) {
        this.aliyunSmsSender = aliyunSmsSender;
        this.jdbcTemplate = jdbcTemplate;
        this.systemConfigService = systemConfigService;
        this.alertDispatcher = alertDispatcher;
        this.exceptionService = exceptionService;
        this.taskService = taskService;
        this.properties = properties;
    }

    /** 总开关优先级：参数配置页 &gt; env 兜底（同 P3-4 那条约定）。 */
    boolean probeEnabled() {
        return systemConfigService.getBoolean(
                SystemConfigService.SMS_DELIVERY_PROBE_ENABLED, properties.enabled());
    }

    @Scheduled(fixedRate = 3_600_000)
    public void probe() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(TASK_KEY, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "未巡检";
        try {
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

    /** 一轮巡检（包级可见：单测直接喂假回执，不出网）。 */
    String runOnce() {
        if (!probeEnabled()) {
            return properties.enabled()
                    ? "短信到达率巡检已由参数配置关闭（不外呼、不判定）"
                    : "短信到达率巡检未启用（env 兜底关闭）";
        }
        if (!aliyunSmsSender.isConfigured()) {
            return "阿里云短信凭据未配齐 ⇒ 无回执可查（不判为健康）";
        }
        // 窗口/阈值/样本量一次读齐，SQL 与判定共用同一个 days（避免两处 clamp 各自漂移）
        int days = clampedLookbackDays();
        Set<String> pairs = targets(days);
        if (pairs.isEmpty()) {
            return "窗口内无短信发送记录 ⇒ 无可查回执（不判为健康）";
        }
        int threshold = systemConfigService.getInt(
                SystemConfigService.SMS_DELIVERY_PROBE_FAIL_ALERT_PERCENT, properties.failAlertPercent());
        int minSample = systemConfigService.getInt(
                SystemConfigService.SMS_DELIVERY_PROBE_MIN_SAMPLE, properties.minSample());

        List<SmsReceipt> receipts = new ArrayList<>();
        int queriedPairs = 0;
        for (String pair : pairs) {
            if (queriedPairs >= MAX_PAIRS_PER_ROUND) {
                break;
            }
            int at = pair.lastIndexOf('@');
            String phone = pair.substring(0, at);
            String sendDate = pair.substring(at + 1);
            receipts.addAll(aliyunSmsSender.querySendDetails(phone, sendDate));
            queriedPairs++;
        }
        SmsDeliveryStats stats = SmsDeliveryStats.of(receipts);
        if (stats.total() < minSample) {
            // 样本不足时判定等于猜：1 条失败就是 100%，会把人训练成忽略告警
            resolvePrevious("样本不足（" + stats.total() + " < min_sample " + minSample + "），本轮不判定");
            return "查询 " + queriedPairs + "/" + pairs.size() + " 组合，样本不足不判定：" + stats.summary();
        }
        if (stats.isFailRatioOver(threshold)) {
            alert(stats, threshold, days);
        } else {
            resolvePrevious("失败占比 " + stats.failPercent() + "% 未超阈值 " + threshold + "%");
        }
        return "窗口 " + days + "d，查询 " + queriedPairs + "/" + pairs.size() + " 组合：" + stats.summary();
    }

    /** (号码@yyyyMMdd) 去重集合：验证码链 + （若有）通知链。 */
    private Set<String> targets(int days) {
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> row : jdbcTemplate.queryForList(SQL_CAPTCHA_TARGETS, days)) {
            addPair(out, row);
        }
        for (Map<String, Object> row : jdbcTemplate.queryForList(SQL_NOTIFY_TARGETS, days)) {
            addPair(out, row);
        }
        return out;
    }

    private static void addPair(Set<String> out, Map<String, Object> row) {
        Object phone = row.get("phone_number");
        Object date = row.get("send_date");
        if (phone == null || date == null || phone.toString().isBlank()) {
            return;
        }
        out.add(phone + "@" + date);
    }

    /** 阿里云只保留最近几天 ⇒ 窗口在代码里夹到 1..6，配多大都查不到更久的数据。 */
    private int clampedLookbackDays() {
        return Math.max(1, Math.min(systemConfigService.getInt(
                SystemConfigService.SMS_DELIVERY_PROBE_LOOKBACK_DAYS, properties.lookbackDays()), 6));
    }

    private void alert(SmsDeliveryStats stats, int threshold, int days) {
        String detail = stats.summary()
                + "\n判据：阿里云 QuerySendDetails 按 (号码,日) 聚合，窗口 " + days + " 天"
                + "\n阈值：失败占比 > " + threshold + "%"
                + "\n注意：返回体无 BizId ⇒ 本告警是聚合口径，不指向具体某一条通知";
        String fingerprint = stats.failed() + "/" + stats.total() + "/" + stats.codes().keySet();
        Instant now = Instant.now();
        boolean fresh = !fingerprint.equals(lastAlertFingerprint)
                || lastAlertAt == null
                || Duration.between(lastAlertAt, now).compareTo(REALERT_INTERVAL) >= 0;
        if (!fresh) {
            log.debug("sms delivery alert suppressed (same fingerprint within realert window)");
            return;
        }
        lastAlertFingerprint = fingerprint;
        lastAlertAt = now;
        log.warn("sms delivery failure ratio over threshold {}", detail);
        exceptionService.report(ALERT_TYPE, "HIGH",
                new OpsExceptionService.ExceptionReport.ExceptionRefs(null, null, null, null),
                "短信到达率异常（失败 " + stats.failPercent() + "%）", detail);
        alertDispatcher.send(ALERT_TYPE, "短信到达率异常", detail,
                Map.of("total", stats.total(), "failed", stats.failed(),
                        "delivered", stats.delivered(), "pending", stats.pending(),
                        "codes", stats.codes(), "thresholdPercent", threshold));
    }

    private void resolvePrevious(String reason) {
        if (lastAlertFingerprint == null) {
            return;
        }
        lastAlertFingerprint = null;
        lastAlertAt = null;
        exceptionService.resolveSystem(ALERT_TYPE, GLOBAL_BUSINESS_KEY, reason);
    }

    /** 单测注入用：让「已告警」状态可复现。 */
    void markAlerted(String fingerprint) {
        this.lastAlertFingerprint = fingerprint;
        this.lastAlertAt = Instant.now();
    }

    String currentAlertFingerprint() {
        return lastAlertFingerprint;
    }
}
