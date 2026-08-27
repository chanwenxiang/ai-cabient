package com.aicabinet.trade.api.support;

import com.aicabinet.trade.service.InvoiceService;
import com.aicabinet.trade.service.LineWithdrawService;
import com.aicabinet.trade.service.MerchantFinanceService;
import com.aicabinet.trade.service.MerchantSkuPricingService;
import com.aicabinet.trade.service.MerchantWithdrawService;
import org.springframework.stereotype.Component;

/** Finance-related dependencies for {@link MerchantPortalControllerSupport}. */
@Component
class MerchantPortalFinanceSupport {

    private final MerchantFinanceService merchantFinanceService;
    private final MerchantSkuPricingService skuPricingService;
    private final LineWithdrawService lineWithdrawService;
    private final MerchantWithdrawService merchantWithdrawService;
    private final InvoiceService invoiceService;

    MerchantPortalFinanceSupport(MerchantFinanceService merchantFinanceService,
                                 MerchantSkuPricingService skuPricingService,
                                 LineWithdrawService lineWithdrawService,
                                 MerchantWithdrawService merchantWithdrawService,
                                 InvoiceService invoiceService) {
        this.merchantFinanceService = merchantFinanceService;
        this.skuPricingService = skuPricingService;
        this.lineWithdrawService = lineWithdrawService;
        this.merchantWithdrawService = merchantWithdrawService;
        this.invoiceService = invoiceService;
    }

    MerchantFinanceService merchantFinanceService() {
        return merchantFinanceService;
    }

    MerchantSkuPricingService skuPricingService() {
        return skuPricingService;
    }

    LineWithdrawService lineWithdrawService() {
        return lineWithdrawService;
    }

    MerchantWithdrawService merchantWithdrawService() {
        return merchantWithdrawService;
    }

    InvoiceService invoiceService() {
        return invoiceService;
    }
}
