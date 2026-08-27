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

    private final MerchantFinanceService merchantFinanceService;
    private final MerchantSkuPricingService skuPricingService;
    private final DisputeService disputeService;
    private final MerchantReplenishmentService merchantReplenishmentService;
    private final MerchantAnalyticsService merchantAnalyticsService;
    private final MerchantNotifyService merchantNotifyService;
    private final MerchantAiInsightService merchantAiInsightService;
    private final LineWithdrawService lineWithdrawService;
    private final MerchantWithdrawService merchantWithdrawService;
    private final InvoiceService invoiceService;

    public MerchantPortalControllerSupport(MerchantFinanceService merchantFinanceService,
                                           MerchantSkuPricingService skuPricingService,
                                           DisputeService disputeService,
                                           MerchantReplenishmentService merchantReplenishmentService,
                                           MerchantAnalyticsService merchantAnalyticsService,
                                           MerchantNotifyService merchantNotifyService,
                                           MerchantAiInsightService merchantAiInsightService,
                                           LineWithdrawService lineWithdrawService,
                                           MerchantWithdrawService merchantWithdrawService,
                                           InvoiceService invoiceService) {
        this.merchantFinanceService = merchantFinanceService;
        this.skuPricingService = skuPricingService;
        this.disputeService = disputeService;
        this.merchantReplenishmentService = merchantReplenishmentService;
        this.merchantAnalyticsService = merchantAnalyticsService;
        this.merchantNotifyService = merchantNotifyService;
        this.merchantAiInsightService = merchantAiInsightService;
        this.lineWithdrawService = lineWithdrawService;
        this.merchantWithdrawService = merchantWithdrawService;
        this.invoiceService = invoiceService;
    }

    public MerchantFinanceService merchantFinanceService() {
        return merchantFinanceService;
    }

    public MerchantSkuPricingService skuPricingService() {
        return skuPricingService;
    }

    public DisputeService disputeService() {
        return disputeService;
    }

    public MerchantReplenishmentService merchantReplenishmentService() {
        return merchantReplenishmentService;
    }

    public MerchantAnalyticsService merchantAnalyticsService() {
        return merchantAnalyticsService;
    }

    public MerchantNotifyService merchantNotifyService() {
        return merchantNotifyService;
    }

    public MerchantAiInsightService merchantAiInsightService() {
        return merchantAiInsightService;
    }

    public LineWithdrawService lineWithdrawService() {
        return lineWithdrawService;
    }

    public MerchantWithdrawService merchantWithdrawService() {
        return merchantWithdrawService;
    }

    public InvoiceService invoiceService() {
        return invoiceService;
    }
}
