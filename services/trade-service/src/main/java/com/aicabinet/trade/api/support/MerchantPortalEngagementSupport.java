package com.aicabinet.trade.api.support;

import com.aicabinet.trade.service.DisputeService;
import com.aicabinet.trade.service.MerchantAiInsightService;
import com.aicabinet.trade.service.MerchantAnalyticsService;
import com.aicabinet.trade.service.MerchantNotifyService;
import com.aicabinet.trade.service.MerchantReplenishmentService;
import org.springframework.stereotype.Component;

/** Engagement and ops dependencies for {@link MerchantPortalControllerSupport}. */
@Component
class MerchantPortalEngagementSupport {

    private final DisputeService disputeService;
    private final MerchantReplenishmentService merchantReplenishmentService;
    private final MerchantAnalyticsService merchantAnalyticsService;
    private final MerchantNotifyService merchantNotifyService;
    private final MerchantAiInsightService merchantAiInsightService;

    MerchantPortalEngagementSupport(DisputeService disputeService,
                                    MerchantReplenishmentService merchantReplenishmentService,
                                    MerchantAnalyticsService merchantAnalyticsService,
                                    MerchantNotifyService merchantNotifyService,
                                    MerchantAiInsightService merchantAiInsightService) {
        this.disputeService = disputeService;
        this.merchantReplenishmentService = merchantReplenishmentService;
        this.merchantAnalyticsService = merchantAnalyticsService;
        this.merchantNotifyService = merchantNotifyService;
        this.merchantAiInsightService = merchantAiInsightService;
    }

    DisputeService disputeService() {
        return disputeService;
    }

    MerchantReplenishmentService merchantReplenishmentService() {
        return merchantReplenishmentService;
    }

    MerchantAnalyticsService merchantAnalyticsService() {
        return merchantAnalyticsService;
    }

    MerchantNotifyService merchantNotifyService() {
        return merchantNotifyService;
    }

    MerchantAiInsightService merchantAiInsightService() {
        return merchantAiInsightService;
    }
}
