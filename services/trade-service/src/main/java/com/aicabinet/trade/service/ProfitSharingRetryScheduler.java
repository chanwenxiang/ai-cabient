package com.aicabinet.trade.service;

import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ProfitSharingRetryScheduler {
    private static final String PROFIT_SHARING_RETRY = "profit-sharing-retry";


    private static final Logger log = LoggerFactory.getLogger(ProfitSharingRetryScheduler.class);

    private final ProfitSharingProperties profitSharingProperties;
    private final OrderRevenueSplitMapper splitRepository;
    private final MerchantMapper merchantRepository;
    private final WeChatProfitSharingService profitSharingService;

    @Autowired
    private ScheduledTaskService taskService;

    public ProfitSharingRetryScheduler(ProfitSharingProperties profitSharingProperties,
                                       OrderRevenueSplitMapper splitRepository,
                                       MerchantMapper merchantRepository,
                                       WeChatProfitSharingService profitSharingService) {
        this.profitSharingProperties = profitSharingProperties;
        this.splitRepository = splitRepository;
        this.merchantRepository = merchantRepository;
        this.profitSharingService = profitSharingService;
    }

    @Scheduled(fixedRate = 900_000)
    @Transactional
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
            List<OrderRevenueSplit> pendingReturns =
                    splitRepository.findTop20ByWechatPendingReturnNoIsNotNullOrderByCreatedAtAsc();
            if (pendingReturns.size() > batch) {
                pendingReturns = pendingReturns.subList(0, batch);
            }
            int polled = profitSharingService.pollPendingReturns(pendingReturns);

            List<OrderRevenueSplit> failedReturns =
                    splitRepository.findTop20ByFailureReasonContainingOrderByCreatedAtAsc("分账回退未成功");
            if (failedReturns.size() > batch) {
                failedReturns = failedReturns.subList(0, batch);
            }
            failedReturns.stream()
                    .map(OrderRevenueSplit::getMerchantId)
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(merchantIds::add);
            Map<String, Merchant> merchants = merchantIds.isEmpty()
                    ? Map.of()
                    : merchantRepository.findAllById(merchantIds).stream()
                    .collect(Collectors.toMap(Merchant::getMerchantId, m -> m, (a, b) -> a));
            int retried = profitSharingService.retryFailedSplits(failedSplits, merchants);
            int returnRetried = profitSharingService.retryFailedReturns(failedReturns, merchants);

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
