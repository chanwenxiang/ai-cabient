package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ScheduledTask;
import com.aicabinet.trade.mapper.ScheduledTaskMapper;
import com.aicabinet.trade.support.ScheduleZones;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 托管任务「超期看护」：以业务表 {@code scheduled_task.last_run_at} 为准，检测
 * {@link XxlJobManagedTasks#KEYS} 里的任务是否仍在按时执行。
 *
 * <p><b>为什么必须有这一层。</b>开启 {@code aicabinet.xxljob.enabled} 后，清单内任务的
 * 内置 {@code @Scheduled} 会经 {@link ScheduledTaskService#tryBegin} <b>无条件让位</b>给调度中心
 * —— 让位条件里只判「开关开 + key 在清单」，<b>不校验调度中心是否可达、执行器是否已注册</b>。
 * 于是任意一次配置漂移（如 {@code XXL_JOB_ADMIN_ADDRESSES} 多带路径前缀）都会让两边同时失效：
 * 任务永久停跑，{@code last_run_at} 只是停止推进，<b>没有报错、没有告警、测试也覆盖不到</b>。</p>
 *
 * <p><b>判据只取 {@code last_run_at}，不看调度台。</b>{@code xxl_job_info.trigger_status=1} 与
 * 每分钟刷新的 {@code trigger_last_time} 在故障期间看起来完全正常（见审计 §18）。</p>
 *
 * <p>命中超期时三路可见：① 运营「异常列表」写入一条 {@code SCHEDULED_TASK_STALE} 系统级异常
 * （恢复后自动关闭）；② 经 {@link OpsAlertDispatcher} 推送钉钉/企微/Webhook；③ 暴露
 * {@code aicabinet_scheduled_task_silence_seconds} / {@code ..._stale_count} 供 Prometheus 告警。</p>
 *
 * <p><b>停机不算超期。</b>判据起点取 {@code max(last_run_at, 进程启动时刻)}：进程没活着的那段时间里
 * 任务本来就不可能执行，把这段静默算作「超期」等于每次重启/开机都误报一次（本机开发环境每天关机，
 * 高频任务阈值只有 20~45 分钟，必中）。豁免的是「停机期」而不是「任务」：进程恢复后仍超过阈值的
 * 静默照报。</p>
 *
 * <p><b>能力边界（必须知道）。</b>看护与被看护的任务同进程，<b>看不到本进程整体消失</b> —— 进程不在时
 * 它也没了。所以它只能治「进程活着但任务不触发」（上一轮 11 个任务成功率 0% 且零告警就是这种），
 * 治不了「进程整个没了」：那种情况要等进程回来才说话（迟到但有用），或交给进程外的探针
 * （如 Prometheus {@code up{job="trade-service"} == 0}）。宿主休眠/待机同理不被豁免 ——
 * 挂起期间时钟推进但 {@code serviceStart} 不变，会被判超期。</p>
 */
@Service
public class ScheduledTaskStaleMonitor {
    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskStaleMonitor.class);

    static final String TASK_KEY = "scheduled-task-stale-monitor";
    /** 告警类型；沿用 ops_exception 的 type 语义。 */
    public static final String ALERT_TYPE = "SCHEDULED_TASK_STALE";
    /** 系统级异常没有设备/会话/订单引用，去重键落到 GLOBAL（见 OpsExceptionService#first）。 */
    private static final String GLOBAL_BUSINESS_KEY = "GLOBAL";
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    /** 超期原因。 */
    enum Reason {
        /** 注册表/托管清单里有这个任务，但运营台没有登记行 —— 连执行记录都无处落。 */
        MISSING_ROW("运营台无登记行"),
        /** 有登记行但没有任何执行记录（从未跑过，或登记行是刚补的 —— 措辞按"记录"说，不臆断事实）。 */
        NEVER_RUN("无执行记录"),
        OVERDUE("超过最大静默时长");

        private final String text;

        Reason(String text) {
            this.text = text;
        }

        String text() {
            return text;
        }
    }

    record StaleTask(String taskKey, String taskName, Reason reason, Instant lastRunAt,
                     Duration silence, Duration maxSilence, boolean afterRestart) {
    }

    /**
     * 本进程启动时刻。早于它的静默不能用「任务没跑」解释 —— 那时进程根本不在。
     * 取 JVM 启动时间：容器/宿主重启会同步推进它，正是停机豁免需要的语义。
     *
     * <p>非 final 且包内可见：单测需要构造「刚重启（uptime &lt; 阈值）」与「已运行很久」两种场景，
     * 而 JVM 启动时间是进程级事实，无法注入。生产代码只读不写。</p>
     */
    volatile Instant serviceStart;

    private final ScheduledTaskMapper taskRepository;
    private final ScheduledTaskService taskService;
    private final OpsAlertDispatcher alertDispatcher;
    private final OpsExceptionService exceptionService;
    private final boolean enabled;
    private final Duration realertInterval;

    /** 每个托管任务的静默秒数（-1 = 无记录），随每次巡检刷新，供 Prometheus 抓取。 */
    private final Map<String, AtomicLong> silenceSeconds = new LinkedHashMap<>();
    private final AtomicLong staleCount = new AtomicLong();

    private volatile String lastAlertFingerprint;
    private volatile Instant lastAlertAt = Instant.EPOCH;

    public ScheduledTaskStaleMonitor(ScheduledTaskMapper taskRepository,
                                     ScheduledTaskService taskService,
                                     OpsAlertDispatcher alertDispatcher,
                                     OpsExceptionService exceptionService,
                                     MeterRegistry meterRegistry,
                                     @Value("${aicabinet.scheduled-task.stale-monitor-enabled:true}")
                                     boolean enabled,
                                     @Value("${aicabinet.scheduled-task.stale-monitor-realart-minutes:360}")
                                     long realertMinutes) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.alertDispatcher = alertDispatcher;
        this.exceptionService = exceptionService;
        this.enabled = enabled;
        this.realertInterval = Duration.ofMinutes(Math.max(1, realertMinutes));
        this.serviceStart = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
        for (String key : XxlJobManagedTasks.KEYS.stream().sorted().toList()) {
            AtomicLong holder = new AtomicLong(-1);
            silenceSeconds.put(key, holder);
            Gauge.builder("aicabinet.scheduled.task.silence.seconds", holder, AtomicLong::doubleValue)
                    .description("托管任务距最近一次执行的秒数（-1 = 无执行记录；已扣除进程停机期）")
                    .tag("task", key)
                    .register(meterRegistry);
        }
        Gauge.builder("aicabinet.scheduled.task.stale.count", staleCount, AtomicLong::doubleValue)
                .description("最近一次巡检判定为超期未执行的托管任务数")
                .register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${aicabinet.scheduled-task.stale-monitor-interval-ms:300000}")
    public void check() {
        if (!enabled) {
            return;
        }
        long start = System.nanoTime();
        if (!taskService.tryBegin(TASK_KEY, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "未巡检";
        try {
            List<StaleTask> stale = scan(Instant.now());
            staleCount.set(stale.size());
            summary = stale.isEmpty()
                    ? "托管任务 " + XxlJobManagedTasks.KEYS.size() + " 个均按时执行"
                    : "超期 " + stale.size() + " 个：" + stale.stream()
                            .map(StaleTask::taskKey).collect(Collectors.joining("、"));
            if (stale.isEmpty()) {
                exceptionService.resolveSystem(ALERT_TYPE, GLOBAL_BUSINESS_KEY, "托管任务已恢复按时执行");
            } else {
                onStale(stale);
            }
        } catch (Exception e) {
            failed = true;
            log.error("scheduled task stale monitor failed", e);
            taskService.finish(TASK_KEY, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(TASK_KEY, "SUCCESS", summary, start);
            }
        }
    }

    /** 只读巡检，便于单测直接断言（不触发告警与指标刷新）。 */
    List<StaleTask> scan(Instant now) {
        return scan(now, serviceStart);
    }

    /**
     * 只读巡检。{@code serviceStart} 显式传入，便于单测构造停机/重启场景。
     *
     * <p>判据起点 = {@code max(lastRunAt, serviceStart)}。{@code NEVER_RUN} 也给一个阈值宽限期：
     * 刚重启时任何任务都还没有「本次启动后」的执行记录，立刻报超期没有信息量。</p>
     */
    List<StaleTask> scan(Instant now, Instant serviceStart) {
        List<StaleTask> stale = new ArrayList<>();
        int exemptedByRestart = 0;
        for (String key : XxlJobManagedTasks.KEYS) {
            Duration maxSilence = ScheduleZones.MAX_SILENCE_BY_TASK.get(key);
            if (maxSilence == null) {
                // 阈值缺失属静态契约漂移，由 scripts/check-xxl-job-wiring.mjs 拦截；此处保守跳过。
                continue;
            }
            ScheduledTask row = taskRepository.selectById(key);
            if (row == null) {
                setSilence(key, -1);
                stale.add(new StaleTask(key, key, Reason.MISSING_ROW, null, null, maxSilence, false));
                continue;
            }
            if (!Boolean.TRUE.equals(row.getEnabled())) {
                setSilence(key, -1);
                continue;
            }
            Instant lastRunAt = row.getLastRunAt();
            if (lastRunAt == null) {
                setSilence(key, -1);
                // 无执行记录：给一个阈值宽限期，让本次启动后的首次调度有机会发生。
                if (Duration.between(serviceStart, now).compareTo(maxSilence) > 0) {
                    stale.add(new StaleTask(key, row.getTaskName(), Reason.NEVER_RUN, null, null,
                            maxSilence, false));
                }
                continue;
            }
            boolean afterRestart = lastRunAt.isBefore(serviceStart);
            if (afterRestart) {
                exemptedByRestart++;
            }
            // 停机期不算任务失职：起点取「最近执行」与「进程启动」中较晚者。
            Instant reference = afterRestart ? serviceStart : lastRunAt;
            Duration silence = Duration.between(reference, now);
            setSilence(key, Math.max(0, silence.getSeconds()));
            if (silence.compareTo(maxSilence) > 0) {
                stale.add(new StaleTask(key, row.getTaskName(), Reason.OVERDUE, lastRunAt, silence,
                        maxSilence, afterRestart));
            }
        }
        if (exemptedByRestart > 0) {
            log.info("静默起算点被进程启动时刻截断（停机豁免）count={} serviceStart={}",
                    exemptedByRestart, serviceStart);
        }
        stale.sort(Comparator.comparing(StaleTask::taskKey));
        return stale;
    }

    private void setSilence(String key, long seconds) {
        AtomicLong holder = silenceSeconds.get(key);
        if (holder != null) {
            holder.set(seconds);
        }
    }

    private void onStale(List<StaleTask> stale) {
        String fingerprint = stale.stream().map(StaleTask::taskKey).collect(Collectors.joining(","));
        log.warn("scheduled task stale detected count={} tasks={}", stale.size(), fingerprint);
        exceptionService.report(ALERT_TYPE, "CRITICAL",
                new OpsExceptionService.ExceptionReport.ExceptionRefs(null, null, null, null),
                "定时任务未按时执行（" + stale.size() + " 个）", detailOf(stale));
        Instant now = Instant.now();
        boolean newSet = !fingerprint.equals(lastAlertFingerprint);
        if (!newSet && Duration.between(lastAlertAt, now).compareTo(realertInterval) < 0) {
            return;
        }
        lastAlertFingerprint = fingerprint;
        lastAlertAt = now;
        alertDispatcher.send(ALERT_TYPE, "定时任务未按时执行",
                detailOf(stale) + "\n判据：scheduled_task.last_run_at（不看调度台状态）",
                Map.of("staleTasks", new ArrayList<>(stale.stream().map(StaleTask::taskKey).toList()),
                        "count", stale.size()));
    }

    private static String detailOf(List<StaleTask> stale) {
        StringBuilder sb = new StringBuilder();
        for (StaleTask t : stale) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(t.taskKey()).append("（").append(t.taskName()).append("）")
                    .append(t.reason().text());
            if (t.lastRunAt() == null) {
                sb.append("；阈值 ").append(t.maxSilence().toMinutes()).append(" 分钟");
            } else if (t.afterRestart()) {
                sb.append("；上次执行 ").append(TIME_FMT.format(t.lastRunAt()))
                        .append("（早于进程启动），本次启动后已静默 ")
                        .append(t.silence().toMinutes()).append(" 分钟，阈值 ")
                        .append(t.maxSilence().toMinutes()).append(" 分钟");
            } else {
                sb.append("；最近执行 ").append(TIME_FMT.format(t.lastRunAt()))
                        .append(" 已静默 ").append(t.silence().toMinutes()).append(" 分钟");
            }
        }
        return sb.toString();
    }
}
