package com.aicabinet.jiangyi.tracker;

import com.aicabinet.jiangyi.client.TradeInternalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 将邑会话看门狗（CB-022，方案 §4.5 时序 5）：模式一设备侧无超时上报
 * （V16 §5.2.14 仅模式二且不用 token），三组超时兜底必须自建：
 * <ul>
 *   <li>开门 15s 无回执（lockStatus/doorStatus 均未到）→ trade open-failed；</li>
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

    private static final long OPEN_TIMEOUT_MS = 15_000L;
    private static final long CLOSE_TIMEOUT_MS = 300_000L;
    private static final long DOING_TIMEOUT_MS = 600_000L;
    private static final String WATCHDOG_LOCK = "jiangyi:watchdog:lock";

    private final CommandTracker commandTracker;
    private final TradeInternalClient tradeInternalClient;
    private final StringRedisTemplate redis;

    public SessionWatchdog(CommandTracker commandTracker,
                           TradeInternalClient tradeInternalClient,
                           StringRedisTemplate redis) {
        this.commandTracker = commandTracker;
        this.tradeInternalClient = tradeInternalClient;
        this.redis = redis;
    }

    @Scheduled(fixedDelay = 10_000L)
    public void sweep() {
        try {
            Boolean locked = redis.opsForValue().setIfAbsent(WATCHDOG_LOCK, "1", Duration.ofSeconds(30));
            if (!Boolean.TRUE.equals(locked)) {
                return;
            }
            try {
                long now = System.currentTimeMillis();
                sweepOpen(now);
                sweepClose(now);
                sweepDoing(now);
            } finally {
                redis.delete(WATCHDOG_LOCK);
            }
        } catch (Exception e) {
            // Redis 抖动：本轮跳过，下趟调度再试（不让调度线程反复抛栈，也保护测试上下文）
            log.warn("jiangyi watchdog sweep skipped: {}", e.getMessage());
        }
    }

    private void sweepOpen(long now) {
        List<CommandTracker.Pending> due = commandTracker.due(
                CommandTracker.Kind.OPEN, now - OPEN_TIMEOUT_MS);
        for (CommandTracker.Pending p : due) {
            log.warn("jiangyi watchdog OPEN timeout sessionId={} deviceId={} — open-failed",
                    p.sessionId(), p.deviceId());
            try {
                tradeInternalClient.postOpenFailed(p.sessionId(), "将邑柜开门指令15秒无设备回执");
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
                tradeInternalClient.recognizeTimeout(p.sessionId());
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
                tradeInternalClient.recognizeTimeout(p.sessionId());
            } catch (Exception e) {
                log.error("jiangyi watchdog doing-timeout notify failed sessionId={}", p.sessionId(), e);
                continue;
            }
            commandTracker.evict(CommandTracker.Kind.DOING, p.sessionId());
        }
    }
}
