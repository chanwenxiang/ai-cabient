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
    private static final String TX_STATUS_NEED_MANUAL = "NEED_MANUAL";
    private static final String ALERT_TYPE_COMPENSATION_TX_STUCK = "COMPENSATION_TX_STUCK";
    private static final String CANCEL = "CANCEL";

    private static final Logger log = LoggerFactory.getLogger(CompensationTaskScheduler.class);

    static final int PROFIT_SHARING_RETURN_MAX_RETRIES = 5;

    private final CompensationTaskMapper taskRepository;
    private final DistributedTransactionMapper txRepository;
    private final TccTransactionCoordinator txCoordinator;
    private final ScheduledTaskService taskService;
    private final OrderRevenueSplitMapper splitRepository;
    private final MerchantMapper merchantRepository;
    private final WeChatProfitSharingService profitSharingService;
    private final ProfitSharingReturnAlertService profitSharingReturnAlertService;
    private final OpsAlertDispatcher alertDispatcher;
    /** 自注入：保证 processTask 上的 @Transactional 经 Spring 代理生效。 */
    private final CompensationTaskScheduler self;

    public CompensationTaskScheduler(CompensationTaskMapper taskRepository,
                                       DistributedTransactionMapper txRepository,
                                       TccTransactionCoordinator txCoordinator,
                                       ScheduledTaskService taskService,
                                       OrderRevenueSplitMapper splitRepository,
                                       MerchantMapper merchantRepository,
                                       WeChatProfitSharingService profitSharingService,
                                       ProfitSharingReturnAlertService profitSharingReturnAlertService,
                                       OpsAlertDispatcher alertDispatcher,
                                       @Lazy CompensationTaskScheduler self) {
        this.taskRepository = taskRepository;
        this.txRepository = txRepository;
        this.txCoordinator = txCoordinator;
        this.taskService = taskService;
        this.splitRepository = splitRepository;
        this.merchantRepository = merchantRepository;
        this.profitSharingService = profitSharingService;
        this.profitSharingReturnAlertService = profitSharingReturnAlertService;
        this.alertDispatcher = alertDispatcher;
        this.self = self;
    }
    @Scheduled(fixedDelay = 30000)
    public void processCompensationTasks() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(COMPENSATION_PROCESS, 600)) {
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
            log.warn("profit sharing return compensation error taskId={}", task.getTaskId(), e);
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
        if (CANCEL.equals(tx.getCompensationSql())) {
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
        long start = System.nanoTime();
        if (!taskService.tryBegin(COMPENSATION_RETRY, 600)) {
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
        }
    }

    private void processTaskSafely(CompensationTask task) {
        try {
            self.processTask(task);
        } catch (Exception e) {
            log.error("Failed to process compensation task: {}", task.getTaskId(), e);
        }
    }

    /**
     * C18：PENDING 分布式事务的真实重试。
     *
     * <p>系统实际产生的补偿语义只有 {@code compensationSql=CANCEL}（见 {@link #executeCompensation}），
     * 对这类事务真实执行 TCC cancel（成功后由 coordinator 落 CANCELLED 终态，不再回到待重试池）；
     * 其余 txType 没有可自动重放的业务动作，不做「只计数不动作」的空转，直接置 NEED_MANUAL 并告警。
     * 重试失败按 retryCount 推进，超过 maxRetry 同样落 NEED_MANUAL 并告警（COMPENSATION_TX_STUCK）。</p>
     */
    private void retryTransactionSafely(DistributedTransaction tx) {
        try {
            if (CANCEL.equals(tx.getCompensationSql())) {
                try {
                    retryCancelCompensation(tx);
                } catch (Exception e) {
                    log.warn("Transaction compensation retry error: txId={}", tx.getTxId(), e);
                    deferOrEscalate(tx, "retry error: " + e.getMessage());
                }
                return;
            }
            markTxNeedManual(tx, "no auto-retryable action for txType=" + tx.getTxType());
        } catch (Exception e) {
            // 保底：单条失败不阻断整批（与 processTaskSafely 同语义）
            log.error("Failed to retry transaction: {}", tx.getTxId(), e);
        }
    }

    private void retryCancelCompensation(DistributedTransaction tx) {
        log.info("Retrying transaction compensation: txId={}, type={}, attempt={}",
                tx.getTxId(), tx.getTxType(), tx.getRetryCount() + 1);
        txCoordinator.cancelTransaction(tx.getTxId());
        DistributedTransaction latest = txRepository.findById(tx.getTxId()).orElse(null);
        if (latest != null && TccTransactionCoordinator.STATUS_CANCELLED.equals(latest.getStatus())) {
            log.info("Transaction compensation retry succeeded: txId={}, status={}",
                    tx.getTxId(), latest.getStatus());
            return;
        }
        deferOrEscalate(tx, "compensation retry did not complete, status="
                + (latest == null ? "GONE" : latest.getStatus()));
    }

    /** 重试失败：retryCount 推进；到达 maxRetry 置 NEED_MANUAL 并告警，否则留待下一轮。 */
    private void deferOrEscalate(DistributedTransaction tx, String reason) {
        int attempt = Math.max(0, tx.getRetryCount()) + 1;
        int maxRetry = tx.getMaxRetry() == null ? 5 : tx.getMaxRetry();
        tx.setRetryCount(attempt);
        tx.setUpdatedAt(Instant.now());
        if (attempt >= maxRetry) {
            markTxNeedManual(tx, reason + " (attempts=" + attempt + ")");
            return;
        }
        txRepository.save(tx);
        log.info("Transaction compensation retry deferred: txId={} attempt={}/{} reason={}",
                tx.getTxId(), attempt, maxRetry, reason);
    }

    private void markTxNeedManual(DistributedTransaction tx, String reason) {
        tx.setStatus(TX_STATUS_NEED_MANUAL);
        tx.setErrorMessage(reason);
        tx.setUpdatedAt(Instant.now());
        txRepository.save(tx);
        log.warn("Compensation tx requires manual handling: txId={} type={} reason={}",
                tx.getTxId(), tx.getTxType(), reason);
        alertDispatcher.trySend(ALERT_TYPE_COMPENSATION_TX_STUCK,
                "分布式事务补偿停滞，需人工处理",
                "txId=" + tx.getTxId() + " type=" + tx.getTxType() + " reason=" + reason,
                Map.of("txId", String.valueOf(tx.getTxId()),
                        "txType", String.valueOf(tx.getTxType())));
    }
}
