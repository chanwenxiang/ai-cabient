package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.DistributedTransaction;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.CompensationTaskMapper;
import com.aicabinet.trade.mapper.DistributedTransactionMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import org.redisson.api.RLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
public class CompensationTaskScheduler {
    private static final String COMPENSATION_PROCESS = "compensation-process";
    private static final String COMPENSATION_RETRY = "compensation-retry";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private static final Logger log = LoggerFactory.getLogger(CompensationTaskScheduler.class);

    static final int PROFIT_SHARING_RETURN_MAX_RETRIES = 5;

    private final CompensationTaskMapper taskRepository;
    private final DistributedTransactionMapper txRepository;
    private final DistributedLockService lockService;
    private final TccTransactionCoordinator txCoordinator;
    private final ScheduledTaskService taskService;
    private final OrderRevenueSplitMapper splitRepository;
    private final MerchantMapper merchantRepository;
    private final WeChatProfitSharingService profitSharingService;
    private final ProfitSharingReturnAlertService profitSharingReturnAlertService;
    /** 自注入：保证 processTask 上的 @Transactional 经 Spring 代理生效。 */
    private final CompensationTaskScheduler self;

    public CompensationTaskScheduler(CompensationTaskMapper taskRepository,
                                       DistributedTransactionMapper txRepository,
                                       DistributedLockService lockService,
                                       TccTransactionCoordinator txCoordinator,
                                       ScheduledTaskService taskService,
                                       OrderRevenueSplitMapper splitRepository,
                                       MerchantMapper merchantRepository,
                                       WeChatProfitSharingService profitSharingService,
                                       ProfitSharingReturnAlertService profitSharingReturnAlertService,
                                       @Lazy CompensationTaskScheduler self) {
        this.taskRepository = taskRepository;
        this.txRepository = txRepository;
        this.lockService = lockService;
        this.txCoordinator = txCoordinator;
        this.taskService = taskService;
        this.splitRepository = splitRepository;
        this.merchantRepository = merchantRepository;
        this.profitSharingService = profitSharingService;
        this.profitSharingReturnAlertService = profitSharingReturnAlertService;
        this.self = self;
    }
    @Scheduled(fixedDelay = 30000)
    public void processCompensationTasks() {
        if (!taskService.isEnabled(COMPENSATION_PROCESS)) {
            return;
        }
        long start = System.nanoTime();
        RLock lock = lockService.acquireLock("compensation:scheduler", 60);
        if (lock == null) {
            log.debug("Another instance is processing compensation tasks");
            return;
        }
        try {
            List<CompensationTask> tasks = taskRepository.findExecutableTasks(Instant.now());
            log.info("Found {} compensation tasks to process", tasks.size());
            
            for (CompensationTask task : tasks) {
                processTaskSafely(task);
            }
            String summary = tasks.isEmpty()
                    ? "本次无补偿任务"
                    : "处理补偿任务 " + tasks.size() + " 条";
            taskService.finish(COMPENSATION_PROCESS, "SUCCESS", summary, start);
        } catch (Exception e) {
            taskService.finish(COMPENSATION_PROCESS, CabinetConstants.ORDER_STATUS_FAILED, e.getMessage(), start);
            throw e;
        } finally {
            lockService.releaseLock(lock);
        }
    }
    
    @Transactional
    public void processTask(CompensationTask task) {
        if (ProfitSharingReturnCompensationService.TASK_TYPE.equals(task.getTaskType())) {
            processProfitSharingReturnTask(task);
            return;
        }
        task.setStatus("PROCESSING");
        taskRepository.save(task);
        
        try {
            DistributedTransaction tx = txRepository.findById(task.getTxId()).orElse(null);
            if (tx == null) {
                task.setStatus(CabinetConstants.ORDER_STATUS_FAILED);
                task.setResult("Transaction not found");
                taskRepository.save(task);
                return;
            }
            
            executeCompensation(tx);

            task.setStatus(STATUS_COMPLETED);
            task.setResult("Compensation executed successfully");
            task.setExecutedAt(Instant.now());
            taskRepository.save(task);

            log.info("Compensation task completed: taskId={}", task.getTaskId());
        } catch (Exception e) {
            task.setStatus(CabinetConstants.ORDER_STATUS_FAILED);
            task.setResult("Error: " + e.getMessage());
            taskRepository.save(task);
            log.error("Compensation task error: taskId={}", task.getTaskId(), e);
        }
    }

    private void processProfitSharingReturnTask(CompensationTask task) {
        task.setStatus("PROCESSING");
        taskRepository.save(task);
        try {
            OrderRevenueSplit split = splitRepository.selectById(task.getTxId());
            if (split == null) {
                finishCompensationTask(task, CabinetConstants.ORDER_STATUS_FAILED, "split not found");
                return;
            }
            if (split.getWechatPendingReturnNo() == null || split.getWechatPendingReturnNo().isBlank()) {
                finishCompensationTask(task, STATUS_COMPLETED, "no pending return");
                return;
            }
            Merchant merchant = merchantRepository.findById(split.getMerchantId()).orElse(null);
            if (merchant == null) {
                finishCompensationTask(task, CabinetConstants.ORDER_STATUS_FAILED, "merchant not found");
                return;
            }
            profitSharingService.refreshPendingReturn(split);
            split = splitRepository.selectById(task.getTxId());
            if (split.getWechatPendingReturnNo() == null || split.getWechatPendingReturnNo().isBlank()) {
                finishCompensationTask(task, STATUS_COMPLETED, "return confirmed");
                return;
            }
            profitSharingService.retryFailedReturns(List.of(split), Map.of(merchant.getMerchantId(), merchant));
            split = splitRepository.selectById(task.getTxId());
            if ((split.getWechatPendingReturnNo() == null || split.getWechatPendingReturnNo().isBlank())
                    && (split.getFailureReason() == null || !split.getFailureReason().contains("分账回退未成功"))) {
                finishCompensationTask(task, STATUS_COMPLETED, "return succeeded");
                return;
            }
            deferProfitSharingReturnTask(task, split.getSplitId());
        } catch (Exception e) {
            deferProfitSharingReturnTask(task, task.getTxId(), "error: " + e.getMessage());
            log.warn("profit sharing return compensation error taskId={}: {}", task.getTaskId(), e.getMessage());
        }
    }

    private void deferProfitSharingReturnTask(CompensationTask task, String splitId) {
        deferProfitSharingReturnTask(task, splitId, "awaiting return confirmation");
    }

    private void deferProfitSharingReturnTask(CompensationTask task, String splitId, String result) {
        int attempt = Math.max(0, task.getRetryCount()) + 1;
        task.setRetryCount(attempt);
        if (attempt >= PROFIT_SHARING_RETURN_MAX_RETRIES) {
            finishCompensationTask(task, CabinetConstants.ORDER_STATUS_FAILED,
                    "max retries exceeded (" + attempt + ") splitId=" + splitId);
            log.warn("profit sharing return compensation exhausted splitId={} attempts={}", splitId, attempt);
            OrderRevenueSplit split = splitRepository.selectById(splitId);
            if (split != null) {
                profitSharingReturnAlertService.sendCompensationExhausted(task, split);
            }
            return;
        }
        long delaySeconds = profitSharingReturnBackoffSeconds(attempt);
        task.setStatus("PENDING");
        task.setScheduledAt(Instant.now().plusSeconds(delaySeconds));
        task.setResult(result + "; retry=" + attempt + "/" + PROFIT_SHARING_RETURN_MAX_RETRIES);
        taskRepository.save(task);
        log.info("profit sharing return retry deferred splitId={} attempt={} delay={}s",
                splitId, attempt, delaySeconds);
    }

  /** 指数退避：60s, 240s, 540s, 960s（封顶 900s）。 */
    static long profitSharingReturnBackoffSeconds(int attempt) {
        if (attempt <= 0) {
            return 60;
        }
        return Math.min(900L, 60L * attempt * attempt);
    }

    private void finishCompensationTask(CompensationTask task, String status, String result) {
        task.setStatus(status);
        task.setResult(result);
        task.setExecutedAt(Instant.now());
        taskRepository.save(task);
        log.info("profit sharing return compensation finished taskId={} status={}", task.getTaskId(), status);
    }
    
    private void executeCompensation(DistributedTransaction tx) {
        if ("CANCEL".equals(tx.getCompensationSql())) {
            txCoordinator.cancelTransaction(tx.getTxId());
        }
    }
    
    public void scheduleCompensation(String txId, String taskType, int delaySeconds) {
        CompensationTask task = new CompensationTask();
        task.setTxId(txId);
        task.setTaskType(taskType);
        task.setScheduledAt(Instant.now().plusSeconds(delaySeconds));
        task.setPriority(0);
        task.setStatus("PENDING");
        taskRepository.save(task);
        
        log.info("Compensation scheduled: txId={}, type={}, delay={}s", txId, taskType, delaySeconds);
    }
    
    @Scheduled(fixedDelay = 60000)
    public void retryFailedTransactions() {
        if (!taskService.isEnabled(COMPENSATION_RETRY)) {
            return;
        }
        long start = System.nanoTime();
        RLock lock = lockService.acquireLock("tx:retry:scheduler", 60);
        if (lock == null) {
            return;
        }
        try {
            List<DistributedTransaction> retryable = txRepository.findRetryableTransactions();
            log.info("Found {} transactions to retry", retryable.size());
            
            for (DistributedTransaction tx : retryable) {
                retryTransactionSafely(tx);
            }
            String summary = retryable.isEmpty()
                    ? "本次无待重试事务"
                    : "重试分布式事务 " + retryable.size() + " 条";
            taskService.finish(COMPENSATION_RETRY, "SUCCESS", summary, start);
        } catch (Exception e) {
            taskService.finish(COMPENSATION_RETRY, CabinetConstants.ORDER_STATUS_FAILED, e.getMessage(), start);
            throw e;
        } finally {
            lockService.releaseLock(lock);
        }
    }

    private void processTaskSafely(CompensationTask task) {
        try {
            self.processTask(task);
        } catch (Exception e) {
            log.error("Failed to process compensation task: {}", task.getTaskId(), e);
        }
    }

    private void retryTransactionSafely(DistributedTransaction tx) {
        try {
            tx.setRetryCount(tx.getRetryCount() + 1);
            txRepository.save(tx);
            log.info("Retrying transaction: txId={}, attempt={}", tx.getTxId(), tx.getRetryCount());
        } catch (Exception e) {
            log.error("Failed to retry transaction: {}", tx.getTxId(), e);
        }
    }
}
