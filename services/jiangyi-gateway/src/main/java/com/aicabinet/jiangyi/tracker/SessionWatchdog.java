package com.aicabinet.jiangyi.tracker;

import com.aicabinet.jiangyi.client.TradeInternalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 将邑会话看门狗（CB-022，方案 §4.5 时序 5）：模式一设备侧无超时上报
 * （V16 §5.2.14 仅模式二且不用 token），三组超时兜底必须自建：
 * <ul>
 *   <li>开门无回执（lockStatus/doorStatus 均未到，阈值 {@code jiangyi.watchdog.open-timeout-ms}，
 *       默认 70s）→ trade open-failed；</li>
 *   <li>关门 300s 无识别结果 → trade recognize-timeout（RECOGNIZING→DISPUTED）；</li>
 *   <li>大模型复核 doing 悬挂 10min 无二次甄别 → trade recognize-timeout。</li>
 * </ul>
 *
 * <p>处置幂等三重保险：① evict 先出队防重复处置；② trade 侧 markOpenDoorFailed /
 * markRecognitionTimeout 均按会话状态 CAS；③ 多实例由 Redis 锁互斥（单实例也开着，
 * 不为省一次 setIfAbsent 引入部署形态耦合）。</p>
 */
@Component
public class SessionWatchdog {

    private static final Logger log = LoggerFactory.getLogger(SessionWatchdog.class);

    private static final long CLOSE_TIMEOUT_MS = 300_000L;

    /**
     * 开门回执超时（R18-1，2026-11-12 审计）：🔴 必须 ≥ trade 侧
     * {@code session-expire.opening-seconds}（默认 60s）+ 调度余量。此前硬编码 15s，
     * 比新开门窗口早 45s：弱网下回执 15~60s 才到时，会话已被本类 open-failed 置 FAILED，
     * 而迟到兜账 v2 只救 CANCELLED（trade 60s 过期处置路径）——货已拿走但无单可结。
     * 阈值调大后：15~60s 内回执正常开门；60~70s 内回执撞 CANCELLED 由兜账 v2 自动转争议单；
     * 70s 后才真判死（此时 open-failed 仅对仍 CREATED/OPENING 的会话生效，与 trade 过期处置 CAS 不冲突）。
     */
    private final long openTimeoutMs;
    private static final long DOING_TIMEOUT_MS = 600_000L;
    /** 模型下发回执超时（rknn 下载 + 机器重启，远长于开门；与 ModelPushController 同值）。 */
    private static final long MODEL_TIMEOUT_MS = 600_000L;
    private static final String WATCHDOG_LOCK = "jiangyi:watchdog:lock";
    /** 租约时长：扫一轮的正常耗时是毫秒级，30s 只是「实例假死」的回收期。 */
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);
    /**
     * fencing 解锁：只有「锁里还是我自己的 token」才删。
     *
     * <p>R19-19（2026-11-12 审计）：原实现 {@code setIfAbsent(lock, "1", 30s)} + finally
     * {@code delete(lock)} —— 锁值是不带身份的常量 "1"。当 A 实例一轮扫超 30s（租约到期，
     * 锁被 B 拿走）后 A 的 finally 仍会执行 delete，**把 B 的锁删掉**，紧接着 C 也能进来：
     * 30s 内三实例并发扫同一批 due，同一会话被重复 open-failed / recognize-timeout。
     * 处置侧虽有 CAS 兜底（不会重复入账），但重复告警与重复调 trade 都是噪音，且 CAS 只保护
     * 「trade 已改状态」的情形，通知本身仍会重发。改成 unique token + Lua 比对删除后，
     * 超时的实例至多「白扫一轮」，不会再解除别人的锁。
     */
    private static final RedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final CommandTracker commandTracker;
    private final TradeInternalClient tradeInternalClient;
    private final StringRedisTemplate redis;

    public SessionWatchdog(CommandTracker commandTracker,
                           TradeInternalClient tradeInternalClient,
                           StringRedisTemplate redis,
                           @Value("${jiangyi.watchdog.open-timeout-ms:70000}") long openTimeoutMs) {
        this.commandTracker = commandTracker;
        this.tradeInternalClient = tradeInternalClient;
        this.redis = redis;
        this.openTimeoutMs = openTimeoutMs;
    }

    @Scheduled(fixedDelay = 10_000L)
    public void sweep() {
        String token = UUID.randomUUID().toString();
        try {
            Boolean locked = redis.opsForValue().setIfAbsent(WATCHDOG_LOCK, token, LOCK_LEASE);
            if (!Boolean.TRUE.equals(locked)) {
                return;
            }
            try {
                long now = System.currentTimeMillis();
                sweepOpen(now);
                sweepClose(now);
                sweepDoing(now);
                sweepModel(now);
            } finally {
                redis.execute(RELEASE_IF_OWNER, List.of(WATCHDOG_LOCK), token);
            }
        } catch (Exception e) {
            // Redis 抖动：本轮跳过，下趟调度再试（不让调度线程反复抛栈，也保护测试上下文）
            log.warn("jiangyi watchdog sweep skipped: {}", e.getMessage());
        }
    }

    private void sweepOpen(long now) {
        List<CommandTracker.Pending> due = commandTracker.due(
                CommandTracker.Kind.OPEN, now - openTimeoutMs);
        for (CommandTracker.Pending p : due) {
            log.warn("jiangyi watchdog OPEN timeout sessionId={} deviceId={} — open-failed",
                    p.sessionId(), p.deviceId());
            try {
                tradeInternalClient.postOpenFailed(p.sessionId(), "将邑柜开门指令无设备回执（网关超时）", p.deviceId());
            } catch (Exception e) {
                log.error("jiangyi watchdog open-failed notify failed sessionId={}", p.sessionId(), e);
                continue; // 下一趟再试（不出队）
            }
            commandTracker.evict(CommandTracker.Kind.OPEN, p.sessionId());
        }
    }

    private void sweepClose(long now) {
        List<CommandTracker.Pending> due = commandTracker.due(
                CommandTracker.Kind.CLOSE, now - CLOSE_TIMEOUT_MS);
        for (CommandTracker.Pending p : due) {
            log.warn("jiangyi watchdog CLOSE timeout sessionId={} — recognize-timeout", p.sessionId());
            try {
                tradeInternalClient.recognizeTimeout(p.sessionId(), p.deviceId(),
                        TradeInternalClient.CAUSE_NO_REPORT, null);
            } catch (Exception e) {
                log.error("jiangyi watchdog recognize-timeout notify failed sessionId={}", p.sessionId(), e);
                continue;
            }
            commandTracker.evict(CommandTracker.Kind.CLOSE, p.sessionId());
        }
    }

    private void sweepDoing(long now) {
        List<CommandTracker.Pending> due = commandTracker.due(
                CommandTracker.Kind.DOING, now - DOING_TIMEOUT_MS);
        for (CommandTracker.Pending p : due) {
            log.warn("jiangyi watchdog DOING timeout sessionId={} — recognize-timeout", p.sessionId());
            try {
                tradeInternalClient.recognizeTimeout(p.sessionId(), p.deviceId(),
                        TradeInternalClient.CAUSE_NO_REPORT, null);
            } catch (Exception e) {
                log.error("jiangyi watchdog doing-timeout notify failed sessionId={}", p.sessionId(), e);
                continue;
            }
            commandTracker.evict(CommandTracker.Kind.DOING, p.sessionId());
        }
    }

    /** 模型下发超时（CB-023）：tracker key = deploymentId，超时置 deployment FAILED。 */
    private void sweepModel(long now) {
        List<CommandTracker.Pending> due = commandTracker.due(
                CommandTracker.Kind.MODEL, now - MODEL_TIMEOUT_MS);
        for (CommandTracker.Pending p : due) {
            log.warn("jiangyi watchdog MODEL timeout deploymentId={} deviceId={} — push-failed",
                    p.sessionId(), p.deviceId());
            try {
                tradeInternalClient.modelPushTimeout(Long.parseLong(p.sessionId()),
                        "模型下发10分钟无设备回执");
            } catch (Exception e) {
                log.error("jiangyi watchdog model-timeout notify failed deploymentId={}", p.sessionId(), e);
                continue;
            }
            commandTracker.evict(CommandTracker.Kind.MODEL, p.sessionId());
        }
    }
}
