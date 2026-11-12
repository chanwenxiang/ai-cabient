package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.trade.domain.CompensationTask;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import com.aicabinet.trade.mapper.CompensationTaskMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.OrderSplitReturnLegMapper;
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

/**
 * 补偿任务调度。TCC/分布式事务死代码已于 L2-3 下线（无业务写入方，历史见
 * docs/THREE_END_FULL_REVIEW_2026-09-27.md §10.2）；当前唯一活跃补偿语义是
 * 分账回退重试（{@code PROFIT_SHARING_RETURN}）。
 */
@Service
public class CompensationTaskScheduler {
    private static final String COMPENSATION_PROCESS = "compensation-process";
    private static final String STATUS_COMPLETED = "COMPLETED";

    private static final Logger log = LoggerFactory.getLogger(CompensationTaskScheduler.class);

    static final int PROFIT_SHARING_RETURN_MAX_RETRIES = 5;

    private final CompensationTaskMapper taskRepository;
    private final ScheduledTaskService taskService;
    private final OrderRevenueSplitMapper splitRepository;
    private final MerchantMapper merchantRepository;
    private final WeChatProfitSharingService profitSharingService;
    private final ProfitSharingReturnAlertService profitSharingReturnAlertService;
    /** R20-9：补偿任务按腿而非按 split 定位，多条腿互不遮挡。 */
    private final OrderSplitReturnLegMapper legRepository;
    /** 自注入：保证 processTask 上的 @Transactional 经 Spring 代理生效。 */
    private final CompensationTaskScheduler self;

    public CompensationTaskScheduler(CompensationTaskMapper taskRepository,
                                       ScheduledTaskService taskService,
                                       OrderRevenueSplitMapper splitRepository,
                                       MerchantMapper merchantRepository,
                                       WeChatProfitSharingService profitSharingService,
                                       ProfitSharingReturnAlertService profitSharingReturnAlertService,
                                       OrderSplitReturnLegMapper legRepository,
                                       @Lazy CompensationTaskScheduler self) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.splitRepository = splitRepository;
        this.merchantRepository = merchantRepository;
        this.profitSharingService = profitSharingService;
        this.profitSharingReturnAlertService = profitSharingReturnAlertService;
        this.legRepository = legRepository;
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
        // L2-3：TCC/分布式事务语义下线后，其余任务类型没有可自动执行的动作，防御性终态
        finishCompensationTask(task, CabinetConstants.ORDER_STATUS_FAILED,
                "unsupported task type: " + task.getTaskType());
    }

    /**
     * R20-9 / CB-085：补偿任务只负责**一条腿**。改前按 split 单值判断「还有没有 pending」，
     * 多腿时一条腿成功就会把整条任务判完成，其余腿再没人查单/重提。
     */
    private void processProfitSharingReturnTask(CompensationTask task) {
        task.setStatus("PROCESSING");
        taskRepository.save(task);
        String splitId = task.getTxId();
        try {
            OrderSplitReturnLeg leg = resolveLeg(task);
            if (leg == null) {
                finishCompensationTask(task, STATUS_COMPLETED, "no pending return leg");
                return;
            }
            splitId = leg.getSplitId();
            if (!ProfitSharingReturnLegService.isOpen(leg.getStatus())) {
                finishCompensationTask(task, STATUS_COMPLETED, "return leg already confirmed");
                return;
            }
            OrderRevenueSplit split = splitRepository.selectById(splitId);
            if (split == null) {
                finishCompensationTask(task, CabinetConstants.ORDER_STATUS_FAILED, "split not found");
                return;
            }
            Merchant merchant = merchantRepository.findById(split.getMerchantId()).orElse(null);
            if (merchant == null) {
                finishCompensationTask(task, CabinetConstants.ORDER_STATUS_FAILED, "merchant not found");
                return;
            }
            profitSharingService.refreshPendingReturn(split, leg);
            leg = legRepository.selectById(leg.getLegId());
            if (leg == null || !ProfitSharingReturnLegService.isOpen(leg.getStatus())) {
                finishCompensationTask(task, STATUS_COMPLETED, "return confirmed");
                return;
            }
            profitSharingService.retryFailedReturnLegs(
                    List.of(leg), Map.of(merchant.getMerchantId(), merchant));
            leg = legRepository.selectById(leg.getLegId());
            if (leg == null || !ProfitSharingReturnLegService.isOpen(leg.getStatus())) {
                finishCompensationTask(task, STATUS_COMPLETED, "return succeeded");
                return;
            }
            deferProfitSharingReturnTask(task, splitId);
        } catch (Exception e) {
            deferProfitSharingReturnTask(task, splitId, "error: " + e.getMessage());
            log.warn("profit sharing return compensation error taskId={}", task.getTaskId(), e);
        }
    }

    /**
     * 新任务的 txId 是 {@code LEG:<legId>}；存量任务仍是裸 {@code splitId}，
     * 此时取该 split 最老一条开放腿跑完即止，不改写历史行。
     */
    private OrderSplitReturnLeg resolveLeg(CompensationTask task) {
        String txId = task.getTxId();
        if (txId == null || txId.isBlank()) {
            return null;
        }
        String prefix = ProfitSharingReturnLegService.LEG_TX_PREFIX;
        if (txId.startsWith(prefix)) {
            try {
                return legRepository.selectById(Long.parseLong(txId.substring(prefix.length())));
            } catch (NumberFormatException e) {
                log.warn("invalid return leg txId={}", txId);
                return null;
            }
        }
        List<OrderSplitReturnLeg> open = legRepository.findOpenBySplitId(txId);
        return open.isEmpty() ? null : open.get(0);
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
                // R20-9：告警金额按腿聚合，不再取 split 上被覆盖过的单值。
                profitSharingReturnAlertService.sendCompensationExhausted(
                        task, split, legRepository.findOpenBySplitId(splitId));
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

    private void processTaskSafely(CompensationTask task) {
        try {
            self.processTask(task);
        } catch (Exception e) {
            log.error("Failed to process compensation task: {}", task.getTaskId(), e);
        }
    }
}
