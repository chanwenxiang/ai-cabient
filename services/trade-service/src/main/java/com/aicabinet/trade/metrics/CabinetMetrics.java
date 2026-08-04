package com.aicabinet.trade.metrics;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class CabinetMetrics {

    private final Counter doorOpenSuccess;
    private final Counter doorOpenFailure;
    private final Counter sessionCompleted;
    private final Counter sessionDisputed;
    private final Counter reconciliationMismatch;
    private final MeterRegistry meterRegistry;
    private final Timer recognizeTimer;
    private final Counter settlementSuccess;
    private final Counter settlementFailure;
    private final Counter paymentCharge;
    private final DistributionSummary chargeAmount;
    private final Timer settlementTimer;
    private final AtomicLong devicesOnline = new AtomicLong();
    private final AtomicLong devicesTotal = new AtomicLong();

    public CabinetMetrics(MeterRegistry registry, DeviceInfoMapper deviceRepository) {
        this.doorOpenSuccess = registry.counter("cabinet.door.open", "result", "success");
        this.doorOpenFailure = registry.counter("cabinet.door.open", "result", "failure");
        this.sessionCompleted = registry.counter("cabinet.session.transition", "state", "COMPLETED");
        this.sessionDisputed = registry.counter("cabinet.session.transition", "state", "DISPUTED");
        this.reconciliationMismatch = registry.counter("cabinet.reconciliation", "status", "MISMATCH");
        this.meterRegistry = registry;
        this.recognizeTimer = registry.timer("cabinet.recognize.duration");
        this.settlementSuccess = registry.counter("cabinet.settlement", "result", "success");
        this.settlementFailure = registry.counter("cabinet.settlement", "result", "failure");
        this.paymentCharge = registry.counter("cabinet.payment.charge");
        this.chargeAmount = DistributionSummary.builder("cabinet.charge.amount")
                .baseUnit("cents").description("Charge amount distribution").register(registry);
        this.settlementTimer = registry.timer("cabinet.settlement.duration");
        registry.gauge("cabinet.devices.online", devicesOnline);
        registry.gauge("cabinet.devices.total", devicesTotal);
        refreshDeviceGauges(deviceRepository);
    }

    public void refreshDeviceGauges(DeviceInfoMapper deviceRepository) {
        devicesTotal.set(deviceRepository.count());
        devicesOnline.set(deviceRepository.countByOnlineStatus("ONLINE"));
    }

    public void recordReconciliationMismatch() { reconciliationMismatch.increment(); }
    public void recordDoorOpen(boolean success) {
        if (success) doorOpenSuccess.increment();
        else doorOpenFailure.increment();
    }
    public void recordSessionState(SessionState state) {
        if (state == SessionState.COMPLETED) sessionCompleted.increment();
        else if (state == SessionState.DISPUTED) sessionDisputed.increment();
    }
    public void recordRecognizeMs(long millis) { recognizeTimer.record(millis, TimeUnit.MILLISECONDS); }
    public void recordSettlementSuccess() { settlementSuccess.increment(); }
    public void recordSettlementFailure() { settlementFailure.increment(); }
    public void recordPaymentCharge(int amountCents) { paymentCharge.increment(); chargeAmount.record(amountCents); }
    public Timer.Sample startSettlementTimer() { return Timer.start(meterRegistry); }
    public void recordSettlementDuration(Timer.Sample sample) { if (sample != null) sample.stop(settlementTimer); }
    public void recordMerchantScopeDenied(String reason) {
        meterRegistry.counter("cabinet.merchant.scope_denied", "reason", reason != null ? reason : "unknown").increment();
    }
}
