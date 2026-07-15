package com.aicabinet.trade.service;

import com.aicabinet.trade.config.ProfitSharingProperties;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
        if (!profitSharingProperties.enabled() || !profitSharingProperties.retryEnabled()) {
            return;
        }
        if (!profitSharingService.isApiReady()) {
            return;
        }
        int batch = Math.min(profitSharingProperties.retryBatchSize(), 20);
        List<OrderRevenueSplit> failed = splitRepository.findTop20ByStatusOrderByCreatedAtAsc("WECHAT_FAILED");
        if (failed.isEmpty()) {
            return;
        }
        if (failed.size() > batch) {
            failed = failed.subList(0, batch);
        }
        Map<String, Merchant> merchants = merchantRepository.findAll().stream()
                .collect(Collectors.toMap(Merchant::getMerchantId, m -> m, (a, b) -> a));
        int retried = profitSharingService.retryFailedSplits(failed, merchants);
        if (retried > 0) {
            log.info("profit sharing retry attempted count={}", retried);
        }
    }
}
