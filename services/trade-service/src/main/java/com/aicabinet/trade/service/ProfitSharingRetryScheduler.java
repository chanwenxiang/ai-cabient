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
        if (!taskService.tryBegin("profit-sharing-retry", 600)) {
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
            if (failedSplits.isEmpty()) {
                summary = "本次无失败分账单";
                return;
            }
            if (failedSplits.size() > batch) {
                failedSplits = failedSplits.subList(0, batch);
            }
            var merchantIds = failedSplits.stream()
                    .map(OrderRevenueSplit::getMerchantId)
                    .filter(id -> id != null && !id.isBlank())
                    .collect(Collectors.toSet());
            Map<String, Merchant> merchants = merchantIds.isEmpty()
                    ? Map.of()
                    : merchantRepository.findAllById(merchantIds).stream()
                    .collect(Collectors.toMap(Merchant::getMerchantId, m -> m, (a, b) -> a));
            int retried = profitSharingService.retryFailedSplits(failedSplits, merchants);
            summary = retried <= 0 ? "本次无失败分账单可重试" : "重试分账 " + retried + " 条";
            if (retried > 0) {
                log.info("profit sharing retry attempted count={}", retried);
            }
        } catch (Exception e) {
            failed = true;
            taskService.finish("profit-sharing-retry", "FAILED", e.getMessage(), start);
            throw e;
        } finally {
            if (!failed) {
                taskService.finish("profit-sharing-retry", "SUCCESS", summary, start);
            }
        }
    }
}
