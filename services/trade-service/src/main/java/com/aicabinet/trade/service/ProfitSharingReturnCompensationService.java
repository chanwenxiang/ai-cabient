package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.CompensationTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 分账回退失败时写入补偿任务，由 {@link CompensationTaskScheduler} 异步重试。
 */
@Service
public class ProfitSharingReturnCompensationService {

    public static final String TASK_TYPE = "PROFIT_SHARING_RETURN";

    private static final Logger log = LoggerFactory.getLogger(ProfitSharingReturnCompensationService.class);

    private final CompensationTaskMapper taskRepository;
    private final DistributedLockService distributedLockService;

    public ProfitSharingReturnCompensationService(CompensationTaskMapper taskRepository,
                                                  DistributedLockService distributedLockService) {
        this.taskRepository = taskRepository;
        this.distributedLockService = distributedLockService;
    }

    @Transactional
    public void scheduleReturnRetry(OrderRevenueSplit split, int delaySeconds) {
        if (split == null || split.getSplitId() == null || split.getSplitId().isBlank()) {
            return;
        }
        if (split.getWechatPendingReturnNo() == null || split.getWechatPendingReturnNo().isBlank()) {
            return;
        }
        if (!distributedLockService.tryLock(returnCompensationLockKey(split.getSplitId()), 60, 5)) {
            log.warn("profit sharing return compensation lock busy splitId={}", split.getSplitId());
            return;
        }
        try {
            if (taskRepository.existsPending(split.getSplitId(), TASK_TYPE)) {
                return;
            }
            CompensationTask task = new CompensationTask();
            task.setTxId(split.getSplitId());
            task.setTaskType(TASK_TYPE);
            task.setPriority(5);
            task.setScheduledAt(Instant.now().plusSeconds(Math.max(30, delaySeconds)));
            task.setStatus("PENDING");
            task.setRetryCount(0);
            taskRepository.save(task);
            log.info("profit sharing return retry scheduled splitId={} outReturnNo={} delay={}s",
                    split.getSplitId(), split.getWechatPendingReturnNo(), delaySeconds);
        } finally {
            distributedLockService.unlock(returnCompensationLockKey(split.getSplitId()));
        }
    }

    static String returnCompensationLockKey(String splitId) {
        return "compensation:profit-sharing-return:" + splitId;
    }
}
