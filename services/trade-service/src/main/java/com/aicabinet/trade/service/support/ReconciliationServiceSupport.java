package com.aicabinet.trade.service.support;

import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.PaymentPlatformBillLineMapper;
import com.aicabinet.trade.mapper.RechargeOrderMapper;
import com.aicabinet.trade.reconciliation.PlatformBillProviderRegistry;
import com.aicabinet.trade.service.DistributedLockService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/** Groups secondary dependencies for {@link com.aicabinet.trade.service.ReconciliationService}. */
@Component
public class ReconciliationServiceSupport {

    private final PaymentPlatformBillLineMapper billLineRepository;
    private final PaymentOperationMapper paymentOperationRepository;
    private final RechargeOrderMapper rechargeRepository;
    private final PlatformBillProviderRegistry billProviderRegistry;
    private final ObjectMapper objectMapper;
    private final CabinetMetrics cabinetMetrics;
    private final DistributedLockService distributedLockService;

    public ReconciliationServiceSupport(PaymentPlatformBillLineMapper billLineRepository,
                                        PaymentOperationMapper paymentOperationRepository,
                                        RechargeOrderMapper rechargeRepository,
                                        PlatformBillProviderRegistry billProviderRegistry,
                                        ObjectMapper objectMapper,
                                        CabinetMetrics cabinetMetrics,
                                        DistributedLockService distributedLockService) {
        this.billLineRepository = billLineRepository;
        this.paymentOperationRepository = paymentOperationRepository;
        this.rechargeRepository = rechargeRepository;
        this.billProviderRegistry = billProviderRegistry;
        this.objectMapper = objectMapper;
        this.cabinetMetrics = cabinetMetrics;
        this.distributedLockService = distributedLockService;
    }

    public PaymentPlatformBillLineMapper billLineRepository() {
        return billLineRepository;
    }

    public PaymentOperationMapper paymentOperationRepository() {
        return paymentOperationRepository;
    }

    public RechargeOrderMapper rechargeRepository() {
        return rechargeRepository;
    }

    public PlatformBillProviderRegistry billProviderRegistry() {
        return billProviderRegistry;
    }

    public ObjectMapper objectMapper() {
        return objectMapper;
    }

    public CabinetMetrics cabinetMetrics() {
        return cabinetMetrics;
    }

    public DistributedLockService distributedLockService() {
        return distributedLockService;
    }
}
