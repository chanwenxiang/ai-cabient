package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ScheduledTaskDto;
import com.aicabinet.common.dto.UpdateScheduledTaskMetaRequest;
import com.aicabinet.common.dto.UpsertScheduledTaskRequest;
import com.aicabinet.trade.domain.ScheduledTask;
import com.aicabinet.trade.mapper.ScheduledTaskMapper;
import com.xxl.job.core.context.XxlJobContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 定时任务管理：启停开关、分布式锁执行守卫、最近执行记录。
 * <p>所有写型定时任务通过 {@link #tryBegin}/{@link #finish} 包裹，保证集群下单实例执行，
 * 并在页面上可查看/启停/手动触发。</p>
 * <p>资金类等 {@link XxlJobManagedTasks}：开启 XXL-JOB 后内置 {@code @Scheduled} 自动让位，
 * 仅调度中心线程或 {@link #runAllowingBuiltin(Runnable)}（运营「立即执行」）可进入。</p>
 */
@Service
public class ScheduledTaskService {
    private static final Logger log = LoggerFactory.getLogger(ScheduledTaskService.class);
    private static final String SCHEDULED_TASK = "SCHEDULED_TASK";


    private static final ThreadLocal<Boolean> ALLOW_BUILTIN = new ThreadLocal<>();

    /**
     * M19：本次 {@link #tryBegin} 返回 false 的原因（线程内）。
     * 调度入口（XXL handler）在 action 结束后读取，用于区分「跳过」与「执行成功」。
     */
    private static final ThreadLocal<String> LAST_SKIP_REASON = new ThreadLocal<>();

    private static final Pattern TASK_KEY_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9_-]{1,62}$");

    private final ScheduledTaskMapper taskRepository;
    private final ScheduledTaskRegistry registry;
    private final DistributedLockService lockService;
    private final AdminAuditService auditService;
    private final boolean xxlJobEnabled;

    public ScheduledTaskService(ScheduledTaskMapper taskRepository,
                                @Lazy ScheduledTaskRegistry registry,
                                DistributedLockService lockService,
                                AdminAuditService auditService,
                                @Value("${aicabinet.xxljob.enabled:false}") boolean xxlJobEnabled) {
        this.taskRepository = taskRepository;
        this.registry = registry;
        this.lockService = lockService;
        this.auditService = auditService;
        this.xxlJobEnabled = xxlJobEnabled;
    }

    @Transactional(readOnly = true)
    public List<ScheduledTaskDto> listAll() {
        return taskRepository.findAllByOrderByTaskKeyAsc().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public ScheduledTaskDto get(String taskKey) {
        return toDto(requireTask(taskKey));
    }

    /** 新建自定义任务行（无代码 runner；仅作登记/启停，立即执行需后续注册）。 */
    @Transactional
    public ScheduledTaskDto create(Long operatorId, UpsertScheduledTaskRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        String taskKey = normalizeTaskKey(req.taskKey());
        return runWithAdminTaskLock(taskKey, () -> doCreate(operatorId, taskKey, req));
    }

    private ScheduledTaskDto doCreate(Long operatorId, String taskKey, UpsertScheduledTaskRequest req) {
        if (taskRepository.selectById(taskKey) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务标识已存在: " + taskKey);
        }
        if (registry.get(taskKey).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务标识与内置任务冲突: " + taskKey);
        }
        ScheduledTask row = new ScheduledTask();
        row.setTaskKey(taskKey);
        row.setTaskName(req.taskName().trim());
        row.setTaskGroup(normalizeGroup(req.taskGroup()));
        row.setScheduleDesc(blankToNull(req.scheduleDesc()));
        row.setEnabled(req.enabled() == null || Boolean.TRUE.equals(req.enabled()));
        row.setRemark(blankToNull(req.remark()));
        row.setUpdatedAt(Instant.now());
        taskRepository.save(row);
        auditService.appendLog(operatorId, "SCHEDULED_TASK_CREATE", SCHEDULED_TASK, taskKey, row.getTaskName());
        return toDto(row);
    }

    @Transactional
    public ScheduledTaskDto updateMeta(Long operatorId, String taskKey, UpdateScheduledTaskMetaRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        return runWithAdminTaskLock(taskKey, () -> doUpdateMeta(operatorId, taskKey, req));
    }

    private ScheduledTaskDto doUpdateMeta(Long operatorId, String taskKey, UpdateScheduledTaskMetaRequest req) {
        ScheduledTask row = requireTaskForUpdate(taskKey);
        row.setTaskName(req.taskName().trim());
        row.setTaskGroup(normalizeGroup(req.taskGroup()));
        row.setScheduleDesc(blankToNull(req.scheduleDesc()));
        row.setRemark(blankToNull(req.remark()));
        row.setUpdatedAt(Instant.now());
        taskRepository.save(row);
        // R13-MA-174：显式清列——updateById 跳 null，scheduleDesc/remark 清空后无法落库（M01 家族）。
        if (row.getScheduleDesc() == null) {
            taskRepository.update(null, Wrappers.<ScheduledTask>lambdaUpdate()
                    .eq(ScheduledTask::getTaskKey, taskKey)
                    .set(ScheduledTask::getScheduleDesc, null));
        }
        if (row.getRemark() == null) {
            taskRepository.update(null, Wrappers.<ScheduledTask>lambdaUpdate()
                    .eq(ScheduledTask::getTaskKey, taskKey)
                    .set(ScheduledTask::getRemark, null));
        }
        auditService.appendLog(operatorId, "SCHEDULED_TASK_UPDATE", SCHEDULED_TASK, taskKey, row.getTaskName());
        return toDto(row);
    }

    @Transactional
    public void delete(Long operatorId, String taskKey) {
        runWithAdminTaskLock(taskKey, () -> {
            doDelete(operatorId, taskKey);
            return null;
        });
    }

    private void doDelete(Long operatorId, String taskKey) {
        if (registry.get(taskKey).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内置任务不可删除，请停用");
        }
        ScheduledTask row = requireTaskForUpdate(taskKey);
        taskRepository.deleteById(taskKey);
        auditService.appendLog(operatorId, "SCHEDULED_TASK_DELETE", SCHEDULED_TASK, taskKey, row.getTaskName());
    }

    @Transactional(readOnly = true)
    public boolean isEnabled(String taskKey) {
        ScheduledTask row = taskRepository.selectById(taskKey);
        return row == null || Boolean.TRUE.equals(row.getEnabled());
    }

    @Transactional
    public ScheduledTaskDto setEnabled(Long operatorId, String taskKey, boolean enabled) {
        return runWithAdminTaskLock(taskKey, () -> doSetEnabled(operatorId, taskKey, enabled));
    }

    private ScheduledTaskDto doSetEnabled(Long operatorId, String taskKey, boolean enabled) {
        ScheduledTask row = requireTaskForUpdate(taskKey);
        row.setEnabled(enabled);
        row.setUpdatedAt(Instant.now());
        taskRepository.save(row);
        auditService.appendLog(operatorId, enabled ? "SCHEDULED_TASK_ENABLE" : "SCHEDULED_TASK_DISABLE",
                SCHEDULED_TASK, taskKey, row.getTaskName());
        return toDto(row);
    }

    /** 更新任务备注（说明这个任务干嘛的），留审计。 */
    @Transactional
    public ScheduledTaskDto setRemark(Long operatorId, String taskKey, String remark) {
        return runWithAdminTaskLock(taskKey, () -> doSetRemark(operatorId, taskKey, remark));
    }

    private ScheduledTaskDto doSetRemark(Long operatorId, String taskKey, String remark) {
        ScheduledTask row = requireTaskForUpdate(taskKey);
        row.setRemark(remark == null ? null : remark.trim());
        row.setUpdatedAt(Instant.now());
        taskRepository.save(row);
        auditService.appendLog(operatorId, "SCHEDULED_TASK_REMARK", SCHEDULED_TASK, taskKey, row.getTaskName());
        return toDto(row);
    }

    /**
     * 运营后台「立即执行」：即使任务已由 XXL 接管，也允许本进程跑一遍（仍走锁与执行记录）。
     */
    public void runAllowingBuiltin(Runnable action) {
        ALLOW_BUILTIN.set(Boolean.TRUE);
        try {
            action.run();
        } finally {
            ALLOW_BUILTIN.remove();
        }
    }

    /** 执行守卫：任务启用检查 + 分布式锁；返回是否允许本次执行。 */
    public boolean tryBegin(String taskKey, long leaseSeconds) {
        if (shouldYieldToXxlJob(taskKey)) {
            recordSkip("builtin scheduler yields to xxl-job");
            return false;
        }
        ScheduledTask row = taskRepository.selectById(taskKey);
        if (row != null && !Boolean.TRUE.equals(row.getEnabled())) {
            recordSkip("task disabled");
            return false;
        }
        if (!lockService.tryLock("job:" + taskKey, leaseSeconds, 0)) {
            recordSkip("lock busy");
            return false;
        }
        // 集群就绪 P-3：显式租约下 Redisson watchdog 不激活，任务体超过租约时
        // 第二实例能进临界区造成双跑——持锁后启动租约心跳按周期续租。
        // R18-6 配套：登记「执行中」标志（finish finally 移除），心跳续租前校验——
        // 防止 finish 已收尾而心跳句柄晚到/漏 cancel 时把锁继续钉在续租上限。
        leaseHolders.put(taskKey, Thread.currentThread().getId());
        startLeaseHeartbeat(taskKey, leaseSeconds, Thread.currentThread().getId());
        LAST_SKIP_REASON.remove();
        return true;
    }

    private static void recordSkip(String reason) {
        LAST_SKIP_REASON.set(reason);
    }

    /** M19：调度入口在 action 结束后调用，取走本次 tryBegin 跳过原因；未跳过返回 null。 */
    public String consumeLastTryBeginSkip() {
        String reason = LAST_SKIP_REASON.get();
        LAST_SKIP_REASON.remove();
        return reason;
    }

    /**
     * M19：把「跳过」落库为执行记录（lastResult=SKIPPED），运营台可与「执行成功」区分。
     * <p>与 {@link #finish} 不同：不释放分布式锁——跳过场景锁可能正被其它实例持有。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSkipped(String taskKey, String message) {
        ScheduledTask row = taskRepository.findByIdForUpdate(taskKey).orElse(null);
        if (row == null) {
            return;
        }
        Instant now = Instant.now();
        row.setLastRunAt(now);
        row.setLastResult("SKIPPED");
        row.setLastMessage(truncate(message, 500));
        row.setLastDurationMs(0L);
        row.setUpdatedAt(now);
        taskRepository.save(row);
    }

    /** 记录执行结果并释放分布式锁（独立事务，外层异常回滚不影响记录）。
     *
     *  <p>R18-6（四端审计 2026-11-12）：cancel+unlock 必须 try/finally——此前裸顺序，
     *  save（REQUIRES_NEW 写库）一旦抛异常，cancel/unlock 被跳过，而心跳仍按 owner
     *  命中续租 ⇒ 锁在、任务不在，全集群静默跳过该任务至续租上限（约 6.6h）。</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(String taskKey, String result, String message, long startNanos) {
        try {
            ScheduledTask row = taskRepository.findByIdForUpdate(taskKey).orElse(null);
            if (row != null) {
                Instant now = Instant.now();
                row.setLastRunAt(now);
                row.setLastResult(result);
                row.setLastMessage(truncate(defaultMessage(result, message), 500));
                row.setLastDurationMs(Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L));
                row.setUpdatedAt(now);
                taskRepository.save(row);
            }
        } finally {
            // 先停心跳再解锁：解锁后 hash 已删、在途续租的 owner 校验必失败，此序无竞态窗口。
            leaseHolders.remove(taskKey);
            cancelLeaseHeartbeat(taskKey);
            lockService.unlock("job:" + taskKey);
        }
    }

    // ── 租约心跳（集群就绪 P-3）──

    /** 续租周期 = 租约 / 3；即使个别续租失败也有两次补救机会。 */
    private static final int HEARTBEAT_DIVISOR = 3;
    /**
     * 续租次数上限：防「任务线程挂死 + 心跳无限续租」把锁永久钉死、堵死实例级自愈。
     * 120 次 × 租约/3 = 租约的 40 倍时长（600s 租约 → 上限约 6.6h），正常任务远达不到；
     * 达到即停止续租并留错误日志，锁按租约自然过期、其他实例可接管。
     */
    private static final int MAX_HEARTBEAT_RENEWALS = 120;

    private final Map<String, ScheduledFuture<?>> leaseHeartbeats = new ConcurrentHashMap<>();
    /** R18-6 配套：taskKey → 持锁线程 id 的「执行中」标志。tryBegin 登记、finish finally 移除；心跳只见标志消失即停。 */
    private final Map<String, Long> leaseHolders = new ConcurrentHashMap<>();
    private ScheduledExecutorService leaseHeartbeatExecutor;

    private synchronized ScheduledExecutorService heartbeatExecutor() {
        if (leaseHeartbeatExecutor == null) {
            leaseHeartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "scheduled-task-lease-heartbeat");
                t.setDaemon(true);
                return t;
            });
        }
        return leaseHeartbeatExecutor;
    }

    /** 任务体可能超过租约：持锁后按租约 1/3 周期续租；锁丢失/达到上限即停（留错误日志）。
     *
     *  <p>R18-7：renew 三态——LOST 才停，TRANSIENT（Redis 瞬断）保留心跳下周期再试；
     *  此前瞬断也返回 false ⇒ 一次抖动即永久停租。R18-8：取消只摘「自己这一代」的句柄
     *  （remove(key, future)），上一轮晚到的失败分支摘不掉新一代心跳。</p>
     */
    private void startLeaseHeartbeat(String taskKey, long leaseSeconds, long holderThreadId) {
        String lockKey = "job:" + taskKey;
        long period = Math.max(1, leaseSeconds / HEARTBEAT_DIVISOR);
        AtomicInteger renewals = new AtomicInteger();
        AtomicReference<ScheduledFuture<?>> selfRef = new AtomicReference<>();
        ScheduledFuture<?> future = heartbeatExecutor().scheduleAtFixedRate(() -> {
            try {
                // R18-6 配套：执行中标志已被 finish finally 摘除（或被新一代覆盖）⇒ 本代已收尾，停
                Long holder = leaseHolders.get(taskKey);
                if (holder == null || holder != holderThreadId) {
                    log.warn("任务锁心跳发现执行标志已消失（finish 已收尾或新一代接管）task={} —— 停止本代续租", taskKey);
                    ScheduledFuture<?> self = selfRef.get();
                    if (self != null) {
                        cancelLeaseHeartbeat(taskKey, self);
                    }
                    return;
                }
                DistributedLockService.RenewOutcome outcome =
                        lockService.renew(lockKey, leaseSeconds, holderThreadId);
                if (outcome == DistributedLockService.RenewOutcome.LOST) {
                    log.error("任务锁已丢失（过期或易主）task={} —— 本次执行存在双跑风险，请检查任务体耗时", taskKey);
                    ScheduledFuture<?> self = selfRef.get();
                    if (self != null) {
                        cancelLeaseHeartbeat(taskKey, self);
                    }
                    return;
                }
                // TRANSIENT：锁状态未知（Redis 瞬断）——保留心跳，下一周期再试
            } catch (Throwable t) {
                log.warn("任务锁续租异常 task={}: {}", taskKey, t.toString());
            }
            if (renewals.incrementAndGet() >= MAX_HEARTBEAT_RENEWALS) {
                log.error("任务锁心跳达到续租上限 task={} renewals={} —— 停止续租，锁将按租约自然过期；超长任务应自行分段", taskKey, MAX_HEARTBEAT_RENEWALS);
                ScheduledFuture<?> self = selfRef.get();
                if (self != null) {
                    cancelLeaseHeartbeat(taskKey, self);
                }
            }
        }, period, period, TimeUnit.SECONDS);
        selfRef.set(future);
        ScheduledFuture<?> prev = leaseHeartbeats.put(taskKey, future);
        if (prev != null) {
            prev.cancel(false);
        }
    }

    /** 无身份摘除（finish 收尾用：摘除当前登记的一代）。 */
    private void cancelLeaseHeartbeat(String taskKey) {
        ScheduledFuture<?> f = leaseHeartbeats.remove(taskKey);
        if (f != null) {
            f.cancel(false);
        }
    }

    /** R18-8：带身份摘除——只有 map 里登记的还是「自己这一代」才摘，上一轮晚到的失败分支摘不掉新一代。 */
    private void cancelLeaseHeartbeat(String taskKey, ScheduledFuture<?> mine) {
        if (leaseHeartbeats.remove(taskKey, mine)) {
            mine.cancel(false);
        }
    }

    @PreDestroy
    public void shutdownLeaseHeartbeat() {
        if (leaseHeartbeatExecutor != null) {
            leaseHeartbeatExecutor.shutdownNow();
        }
    }

    /** XXL 已接管且当前不是 XXL 线程 / 运营强制执行时，内置调度让位。 */
    boolean shouldYieldToXxlJob(String taskKey) {
        if (!xxlJobEnabled || !XxlJobManagedTasks.isManaged(taskKey)) {
            return false;
        }
        if (Boolean.TRUE.equals(ALLOW_BUILTIN.get())) {
            return false;
        }
        return !invokedByXxlJob();
    }

    private static boolean invokedByXxlJob() {
        try {
            return XxlJobContext.getXxlJobContext() != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 未传说明时的兜底；正常任务应写入本次处理条数/快照等结果。 */
    private static String defaultMessage(String result, String message) {
        if (message != null && !message.isBlank()) {
            return message.trim();
        }
        if ("FAILED".equals(result)) {
            return "执行失败";
        }
        if ("SKIPPED".equals(result)) {
            return "已跳过";
        }
        return "本次无处理";
    }

    private ScheduledTask requireTask(String taskKey) {
        ScheduledTask row = taskRepository.selectById(taskKey);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskKey);
        }
        return row;
    }

    private ScheduledTask requireTaskForUpdate(String taskKey) {
        return taskRepository.findByIdForUpdate(taskKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在: " + taskKey));
    }

    static String scheduledTaskAdminLockKey(String taskKey) {
        return "scheduled-task:admin:" + taskKey;
    }

    private <T> T runWithAdminTaskLock(String taskKey, java.util.function.Supplier<T> action) {
        String key = scheduledTaskAdminLockKey(taskKey);
        if (!lockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "定时任务配置处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            lockService.unlock(key);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String normalizeTaskKey(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务标识不能为空");
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        if (!TASK_KEY_PATTERN.matcher(key).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "任务标识须为小写字母开头，仅含字母数字_-，长度 2–63");
        }
        return key;
    }

    private static String normalizeGroup(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "任务分组不能为空");
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private ScheduledTaskDto toDto(ScheduledTask row) {
        return new ScheduledTaskDto(
                row.getTaskKey(),
                row.getTaskName(),
                row.getTaskGroup(),
                row.getScheduleDesc(),
                Boolean.TRUE.equals(row.getEnabled()),
                row.getLastRunAt(),
                row.getLastResult(),
                row.getLastMessage(),
                row.getLastDurationMs(),
                row.getRemark(),
                registry.get(row.getTaskKey()).isPresent());
    }
}
