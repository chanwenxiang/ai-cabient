package com.aicabinet.trade.service;

import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.OrderSplitReturnLeg;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.OrderSplitReturnLegMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProfitSharingRetryScheduler {
    private static final String PROFIT_SHARING_RETRY = "profit-sharing-retry";


    private static final Logger log = LoggerFactory.getLogger(ProfitSharingRetryScheduler.class);

    private final ProfitSharingProperties profitSharingProperties;
    private final OrderRevenueSplitMapper splitRepository;
    private final OrderSplitReturnLegMapper legRepository;
    private final MerchantMapper merchantRepository;
    private final WeChatProfitSharingService profitSharingService;
    private final ScheduledTaskService taskService;

    public ProfitSharingRetryScheduler(ProfitSharingProperties profitSharingProperties,
                                       OrderRevenueSplitMapper splitRepository,
                                       OrderSplitReturnLegMapper legRepository,
                                       MerchantMapper merchantRepository,
                                       WeChatProfitSharingService profitSharingService,
                                       ScheduledTaskService taskService) {
        this.profitSharingProperties = profitSharingProperties;
        this.splitRepository = splitRepository;
        this.legRepository = legRepository;
        this.merchantRepository = merchantRepository;
        this.profitSharingService = profitSharingService;
        this.taskService = taskService;
    }

    /**
     * 调度入口**不加事务**：内部 submitSplit 是「短事务 → HTTP → 短事务」三段式，
     * 外层事务会让微信 HTTP 又被包进 DB 事务，且任一坏行抛错会回滚整批。
     */
    @Scheduled(fixedRate = 900_000)
    public void retryFailedSplits() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(PROFIT_SHARING_RETRY, 600)) {
            return;
        }
        boolean failed = false;
        String summary = "本次无失败分账单";
        try {
            if (!profitSharingProperties.enabled() || !profitSharingProperties.retryEnabled()) {
                summary = "分账未启用";
                return;
            }
            if (!profitSharingService.isApiReady()) {
                summary = "分账接口未就绪";
                return;
            }
            int batch = Math.min(profitSharingProperties.retryBatchSize(), 20);
            List<OrderRevenueSplit> failedSplits =
                    splitRepository.findTop20ByStatusOrderByCreatedAtAsc("WECHAT_FAILED");
            if (failedSplits.size() > batch) {
                failedSplits = failedSplits.subList(0, batch);
            }
            var merchantIds = new java.util.HashSet<String>();
            failedSplits.stream()
                    .map(OrderRevenueSplit::getMerchantId)
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(merchantIds::add);
            // R20-9 / CB-085：回退的待办来源改成「腿表」，不再扫 split 上那对被覆盖的单值列。
            // findOpenLegs 自带 ORDER BY leg_id + LIMIT（铁律 88②），按腿逐条查单/重提。
            List<OrderSplitReturnLeg> openLegs = legRepository.findOpenLegs(batch);
            List<OrderSplitReturnLeg> failedLegs = openLegs.stream()
                    .filter(l -> ProfitSharingReturnLegService.STATUS_FAILED.equals(l.getStatus()))
                    .toList();
            var legSplitIds = new java.util.LinkedHashSet<String>();
            openLegs.stream().map(OrderSplitReturnLeg::getSplitId)
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(legSplitIds::add);
            if (!legSplitIds.isEmpty()) {
                splitRepository.selectBatchIds(legSplitIds).stream()
                        .map(OrderRevenueSplit::getMerchantId)
                        .filter(id -> id != null && !id.isBlank())
                        .forEach(merchantIds::add);
            }
            Map<String, Merchant> merchants = merchantIds.isEmpty()
                    ? Map.of()
                    : merchantRepository.findAllById(merchantIds).stream()
                    .collect(Collectors.toMap(Merchant::getMerchantId, m -> m, (a, b) -> a));
            int retried = profitSharingService.retryFailedSplits(failedSplits, merchants);
            int polled = profitSharingService.pollOpenReturnLegs(openLegs);
            int returnRetried = profitSharingService.retryFailedReturnLegs(failedLegs, merchants);

            summary = buildSummary(retried, polled, returnRetried);
            if (retried > 0 || polled > 0 || returnRetried > 0) {
                log.info("profit sharing retry splits={} polledReturns={} retriedReturns={}",
                        retried, polled, returnRetried);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish(PROFIT_SHARING_RETRY, "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish(PROFIT_SHARING_RETRY, "SUCCESS", summary, start);
            }
        }
    }

    private static String buildSummary(int retried, int polled, int returnRetried) {
        if (retried <= 0 && polled <= 0 && returnRetried <= 0) {
            return "本次无失败分账单";
        }
        StringBuilder sb = new StringBuilder();
        if (retried > 0) {
            sb.append("重试分账 ").append(retried).append(" 条");
        }
        if (polled > 0) {
            if (!sb.isEmpty()) sb.append("；");
            sb.append("确认回退 ").append(polled).append(" 条");
        }
        if (returnRetried > 0) {
            if (!sb.isEmpty()) sb.append("；");
            sb.append("重试回退 ").append(returnRetried).append(" 条");
        }
        return sb.toString();
    }
}
