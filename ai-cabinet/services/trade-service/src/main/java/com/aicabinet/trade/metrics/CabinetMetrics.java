package com.aicabinet.trade.metrics;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.repository.DeviceInfoRepository;
import io.micrometer.core.instrument.Counter;
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
    private final AtomicLong devicesOnline = new AtomicLong();
    private final AtomicLong devicesTotal = new AtomicLong();

    public CabinetMetrics(MeterRegistry registry, DeviceInfoRepository deviceRepository) {
        this.doorOpenSuccess = registry.counter("cabinet.door.open", "result", "success");
        this.doorOpenFailure = registry.counter("cabinet.door.open", "result", "failure");
        this.sessionCompleted = registry.counter("cabinet.session.transition", "state", "COMPLETED");
        this.sessionDisputed = registry.counter("cabinet.session.transition", "state", "DISPUTED");
        this.reconciliationMismatch = registry.counter("cabinet.reconciliation", "status", "MISMATCH");
        this.meterRegistry = registry;
        this.recognizeTimer = registry.timer("cabinet.recognize.duration");
        registry.gauge("cabinet.devices.online", devicesOnline);
        registry.gauge("cabinet.devices.total", devicesTotal);
        refreshDeviceGauges(deviceRepository);
    }

    public void refreshDeviceGauges(DeviceInfoRepository deviceRepository) {
        devicesTotal.set(deviceRepository.count());
        devicesOnline.set(deviceRepository.findAll().stream()
                .filter(d -> "ONLINE".equalsIgnoreCase(d.getOnlineStatus()))
                .count());
    }

    public void recordReconciliationMismatch() {
        reconciliationMismatch.increment();
    }

    public void recordDoorOpen(boolean success) {
        if (success) {
            doorOpenSuccess.increment();
        } else {
            doorOpenFailure.increment();
        }
    }

    public void recordSessionState(SessionState state) {
        if (state == SessionState.COMPLETED) {
            sessionCompleted.increment();
        } else if (state == SessionState.DISPUTED) {
            sessionDisputed.increment();
        }
    }

    public void recordRecognizeMs(long millis) {
        recognizeTimer.record(millis, TimeUnit.MILLISECONDS);
    }

    public void recordMerchantScopeDenied(String reason) {
        String tag = reason != null && !reason.isBlank() ? reason : "unknown";
        meterRegistry.counter("cabinet.merchant.scope_denied", "reason", tag).increment();
    }
}
