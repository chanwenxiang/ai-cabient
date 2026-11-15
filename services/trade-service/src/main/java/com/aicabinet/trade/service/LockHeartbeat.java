package com.aicabinet.trade.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * MA-274（C13/R23Q-13）：订单支付锁心跳。
 *
 * <p>{@code OrderPaymentService#chargeOrder} 在 DB 事务内发渠道 HTTP（H41 已用 CHARGE_PENDING
 * 痕迹兜住「无痕双扣」），持锁时长可达分钟级，而 Redis 锁租约固定 60s 且无续租 ⇒ 到点被第二
 * 实例接管双跑。本组件按 租约/3 周期调用 {@link DistributedLockService#renew} 续租；
 * {@code LOST}（锁已过期/易主）只记 ERROR 停心跳——任务体仍在跑，此刻不能提交新事务，
 * 但必须把「双跑风险」喊出来，而不是静默装没听见。</p>
 *
 * <p>刻意做成普通类而非 Spring bean：单一守护线程、随 OrderPaymentService 构造即建，
 * 不进容器装配面；测试可直接 {@code new LockHeartbeat(mock, 1, 0)} 用秒级参数验证行为。</p>
 */
public class LockHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(LockHeartbeat.class);

    private final DistributedLockService lockService;
    private final long leaseSeconds;
    private final long periodSeconds;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "order-payment-lock-heartbeat");
        t.setDaemon(true);
        return t;
    });

    public LockHeartbeat(DistributedLockService lockService, long leaseSeconds, long periodSeconds) {
        this.lockService = lockService;
        this.leaseSeconds = leaseSeconds;
        this.periodSeconds = periodSeconds;
    }

    /** 启动心跳（调用方持锁线程的 threadId 由调用方传入——renew 的 owner 校验需要它）。 */
    public ScheduledFuture<?> start(String lockKey, long holderThreadId) {
        return executor.scheduleAtFixedRate(() -> {
            try {
                DistributedLockService.RenewOutcome outcome =
                        lockService.renew(lockKey, leaseSeconds, holderThreadId);
                if (outcome == DistributedLockService.RenewOutcome.LOST) {
                    // 锁已易主：续租不再有意义，停心跳并大声报出来（双跑风险可见）
                    log.error("订单支付锁已丢失（过期或易主）lockKey={} —— 任务体仍在执行，存在双跑风险", lockKey);
                    throw new IllegalStateException("lock lost: " + lockKey);
                }
                // TRANSIENT：Redis 瞬断，锁状态未知——保留心跳，下一周期再试
            } catch (IllegalStateException lost) {
                throw lost; // 让 scheduleAtFixedRate 取消后续执行（异常会吞掉本任务）
            } catch (Throwable t) {
                log.warn("订单支付锁心跳异常 lockKey={}: {}", lockKey, t.toString());
            }
        }, periodSeconds, periodSeconds, TimeUnit.SECONDS);
    }

    /** 停止心跳（finally 里解锁前调用；已取消的任务再取消是幂等的）。 */
    public void stop(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }
}
