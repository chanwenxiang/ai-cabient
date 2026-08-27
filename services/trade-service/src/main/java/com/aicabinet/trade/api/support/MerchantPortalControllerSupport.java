package com.aicabinet.trade.api.support;

import com.aicabinet.trade.service.DisputeService;
import com.aicabinet.trade.service.InvoiceService;
import com.aicabinet.trade.service.LineWithdrawService;
import com.aicabinet.trade.service.MerchantAiInsightService;
import com.aicabinet.trade.service.MerchantAnalyticsService;
import com.aicabinet.trade.service.MerchantFinanceService;
import com.aicabinet.trade.service.MerchantNotifyService;
import com.aicabinet.trade.service.MerchantReplenishmentService;
import com.aicabinet.trade.service.MerchantSkuPricingService;
import com.aicabinet.trade.service.MerchantWithdrawService;
import org.springframework.stereotype.Component;

/** Groups secondary dependencies for {@link com.aicabinet.trade.api.MerchantPortalController}. */
@Component
public class MerchantPortalControllerSupport {

    private final MerchantPortalFinanceSupport finance;
    private final MerchantPortalEngagementSupport engagement;

    public MerchantPortalControllerSupport(MerchantPortalFinanceSupport finance,
                                           MerchantPortalEngagementSupport engagement) {
        this.finance = finance;
        this.engagement = engagement;
    }

    public MerchantFinanceService merchantFinanceService() {
        return finance.merchantFinanceService();
    }

    public MerchantSkuPricingService skuPricingService() {
        return finance.skuPricingService();
    }

    public DisputeService disputeService() {
        return engagement.disputeService();
    }

    public MerchantReplenishmentService merchantReplenishmentService() {
        return engagement.merchantReplenishmentService();
    }

    public MerchantAnalyticsService merchantAnalyticsService() {
        return engagement.merchantAnalyticsService();
    }

    public MerchantNotifyService merchantNotifyService() {
        return engagement.merchantNotifyService();
    }

    public MerchantAiInsightService merchantAiInsightService() {
        return engagement.merchantAiInsightService();
    }

    public LineWithdrawService lineWithdrawService() {
        return finance.lineWithdrawService();
    }

    public MerchantWithdrawService merchantWithdrawService() {
        return finance.merchantWithdrawService();
    }

    public InvoiceService invoiceService() {
        return finance.invoiceService();
    }
}
