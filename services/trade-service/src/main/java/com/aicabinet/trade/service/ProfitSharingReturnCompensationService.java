package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import com.aicabinet.trade.mapper.CompensationTaskMapper;
import com.aicabinet.trade.mapper.OrderSplitReturnLegMapper;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分账回退失败时写入补偿任务，由 {@link CompensationTaskScheduler} 异步重试。
 *
 * <p>R20-9 / CB-085：改前以 {@code splitId} 作 {@code txId} 且
 * {@code existsPending(splitId, …)} 去重 ⇒ 同一个 split 只允许一个 PENDING 补偿任务，
 * 多轮部分退时第二轮回退连「查单 / 重提」的调度槽都被吞（不只是金额被覆盖）。
 * 现在一条腿一个 {@code LEG:<legId>} 键，互不遮挡。
 */
@Service
public class ProfitSharingReturnCompensationService {

    public static final String TASK_TYPE = "PROFIT_SHARING_RETURN";

    private static final Logger log = LoggerFactory.getLogger(ProfitSharingReturnCompensationService.class);

    private final CompensationTaskMapper taskRepository;
    private final DistributedLockService distributedLockService;
    private final OrderSplitReturnLegMapper legRepository;

    public ProfitSharingReturnCompensationService(CompensationTaskMapper taskRepository,
                                                  DistributedLockService distributedLockService,
                                                  OrderSplitReturnLegMapper legRepository) {
        this.taskRepository = taskRepository;
        this.distributedLockService = distributedLockService;
        this.legRepository = legRepository;
    }

    /** 存量兼容入口：仍按 split 找齐所有开放腿，逐腿排任务。 */
    @Transactional
    public void scheduleReturnRetry(OrderRevenueSplit split, int delaySeconds) {
        if (split == null || split.getSplitId() == null || split.getSplitId().isBlank()) {
            return;
        }
        scheduleReturnRetry(split, legRepository.findOpenBySplitId(split.getSplitId()), delaySeconds);
    }

    /**
     * 按腿排补偿任务。每条腿一个 txId，重复调用由 {@code existsPending} 按腿去重；
     * 锁仍按 split 粒度（一次调用把该 split 的所有腿一起排完，无需按腿抢锁）。
     */
    @Transactional
    public void scheduleReturnRetry(OrderRevenueSplit split,
                                    List<OrderSplitReturnLeg> openLegs,
                                    int delaySeconds) {
        if (split == null || split.getSplitId() == null || split.getSplitId().isBlank()
                || openLegs == null || openLegs.isEmpty()) {
            return;
        }
        String lockKey = returnCompensationLockKey(split.getSplitId());
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            log.warn("profit sharing return compensation lock busy splitId={}", split.getSplitId());
            return;
        }
        try {
            for (OrderSplitReturnLeg leg : openLegs) {
                if (leg == null || leg.getLegId() == null
                        || !ProfitSharingReturnLegService.isOpen(leg.getStatus())) {
                    continue;
                }
                String txId = ProfitSharingReturnLegService.legTxId(leg.getLegId());
                if (taskRepository.existsPending(txId, TASK_TYPE)) {
                    continue;
                }
                CompensationTask task = new CompensationTask();
                task.setTxId(txId);
                task.setTaskType(TASK_TYPE);
                task.setPriority(5);
                task.setScheduledAt(Instant.now().plusSeconds(Math.max(30, delaySeconds)));
                task.setStatus("PENDING");
                task.setRetryCount(0);
                taskRepository.save(task);
                log.info("profit sharing return retry scheduled splitId={} legId={} outReturnNo={} cents={} delay={}s",
                        split.getSplitId(), leg.getLegId(), leg.getOutReturnNo(), leg.getCents(), delaySeconds);
            }
        } finally {
            distributedLockService.unlock(lockKey);
        }
    }

    static String returnCompensationLockKey(String splitId) {
        return "compensation:profit-sharing-return:" + splitId;
    }
}
