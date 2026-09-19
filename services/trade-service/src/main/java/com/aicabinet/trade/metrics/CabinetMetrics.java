package com.aicabinet.trade.metrics;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class CabinetMetrics {
    private static final String RESULT = "result";


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
    private final DistributionSummary recognitionConfidence;
    private final AtomicLong devicesOnline = new AtomicLong();
    private final AtomicLong devicesTotal = new AtomicLong();
    private final AtomicLong edgeFirmwareOutdated = new AtomicLong();
    private final String expectedFirmware;

    public CabinetMetrics(MeterRegistry registry, DeviceInfoMapper deviceRepository,
                          @Value("${aicabinet.edge.expected-firmware:}") String expectedFirmware) {
        this.expectedFirmware = expectedFirmware;
        this.doorOpenSuccess = registry.counter("cabinet.door.open", RESULT, "success");
        this.doorOpenFailure = registry.counter("cabinet.door.open", RESULT, "failure");
        this.sessionCompleted = registry.counter("cabinet.session.transition", "state", "COMPLETED");
        this.sessionDisputed = registry.counter("cabinet.session.transition", "state", "DISPUTED");
        this.reconciliationMismatch = registry.counter("cabinet.reconciliation", "status", "MISMATCH");
        this.meterRegistry = registry;
        this.recognizeTimer = registry.timer("cabinet.recognize.duration");
        this.settlementSuccess = registry.counter("cabinet.settlement", RESULT, "success");
        this.settlementFailure = registry.counter("cabinet.settlement", RESULT, "failure");
        this.paymentCharge = registry.counter("cabinet.payment.charge");
        this.chargeAmount = DistributionSummary.builder("cabinet.charge.amount")
                .baseUnit("cents").description("Charge amount distribution").register(registry);
        this.settlementTimer = registry.timer("cabinet.settlement.duration");
        registry.gauge("cabinet.devices.online", devicesOnline);
        // 注意：**不要**把此 Gauge 命名为 `cabinet.devices.total`。
        // Micrometer 把 `_total` 视为 Counter 的保留后缀，导出时会把 Gauge 名末尾的 `_total` 剥掉
        // ⇒ 实测 `cabinet.devices.total` 在 /actuator/prometheus 里是 `cabinet_devices`，
        // 于是所有按 `cabinet_devices_total` 写的告警/看板静默失效（永不触发）。
        // 复现：registry.gauge("x.y.total", …) → x_y；registry.gauge("x.y.count", …) → x_y_count。
        // 用 `.count` 既避开该坑又保留「这是总数」的语义。
        registry.gauge("cabinet.devices.count", devicesTotal);
        this.recognitionConfidence = DistributionSummary.builder("cabinet.recognition.confidence")
                .description("平台采纳的识别结果整体置信度分布（0–1），按需人工复核率一起看")
                .baseUnit("ratio")
                .register(registry);
        // 边缘盒固件漂移：**在线**设备中固件版本 ≠ 期望版本的台数（P0-1 阶段 B「边缘盒监控」）。
        // 未配置期望版本（aicabinet.edge.expected-firmware 为空）时置 **-1** 而不是 0 ——
        // 告警判据写 `> 0`，未配置时就不会误报「固件全过期」；0 会被读成「全部合规」而掩盖未配置。
        registry.gauge("cabinet.edge.firmware.outdated.count", edgeFirmwareOutdated);
        refreshDeviceGauges(deviceRepository);
    }

    public void refreshDeviceGauges(DeviceInfoMapper deviceRepository) {
        devicesTotal.set(deviceRepository.count());
        devicesOnline.set(deviceRepository.countByOnlineStatus("ONLINE"));
        if (expectedFirmware == null || expectedFirmware.isBlank()) {
            edgeFirmwareOutdated.set(-1);
        } else {
            edgeFirmwareOutdated.set(
                    deviceRepository.countByOnlineStatusAndFirmwareVersionNot("ONLINE", expectedFirmware.trim()));
        }
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

    /**
     * 平台采纳了一份识别结果（唯一收敛点 {@code RecognitionResultWriter} 调用）。
     *
     * <p>{@code need_review} 这个标签是「准确率」的**代理**之一：分母是全部采纳次数、分子是需人工复核次数
     * ⇒ {@code need_review=true} 占比升高即意味着识别质量在下降。真正的**真值**来自人工复核结论
     * （见 {@link #recordHumanVerdict(String, String)}），need_review 只是它的预警信号。
     */
    public void recordRecognition(Float overallConfidence, boolean needReview) {
        if (overallConfidence != null && Float.isFinite(overallConfidence)) {
            recognitionConfidence.record(overallConfidence);
        }
        meterRegistry.counter("cabinet.recognition.recorded",
                "need_review", Boolean.toString(needReview)).increment();
    }

    /** 端侧直报入站的受理结论（PROCESSED / ALREADY_HANDLED / TOO_EARLY / CANCELLED）。 */
    public void recordEdgeIngest(String outcome) {
        meterRegistry.counter("cabinet.edge.ingest",
                "outcome", outcome == null ? "unknown" : outcome).increment();
    }

    /**
     * 人工复核结论 —— 识别准确率的**真值来源**（P0-1 阶段 B）。
     *
     * <p>系统内没有「识别结果 vs 云端复核」的对照（不做云端识别），但**人工争议结案本身就是标注**：
     * 运营/商户在争议单上做的处置就是对识别结果的对错判定 ——
     * <ul>
     *   <li>{@code KEEP}（维持原账单）⇒ 账单与实物一致 ⇒ {@code verdict=correct}；</li>
     *   <li>{@code WAIVE}（免单退款）/ {@code ADJUST}（人工改单）/ {@code CONFIRM}（按确认清单结算）
     *       ⇒ 原账单与实物不符 ⇒ {@code verdict=wrong}。</li>
     * </ul>
     * 准确率 = {@code correct / (correct + wrong)}。唯一调用点 {@code DisputeService} 的结案分支。
     *
     * <p><b>口径诚实声明</b>：分母是**被提起争议**的会话，不是全部会话。用户只在「觉得被扣错」时才提争议
     * ⇒ 这是**争议子集上的准确率**（有偏样本），会**系统性低于**全量准确率。它的价值在于**趋势与下钻**
     * （按 {@code review_code} 看哪类失败最多），**不可**读作「全站识别准确率」。
     *
     * @param verdict     {@code correct} / {@code wrong}
     * @param reviewCode  工单的失败分类（{@code MOCK}/{@code EMPTY}/{@code LOW_CONF}/{@code UNMAPPED}/
     *                    {@code WHITELIST}/{@code GRAVITY_MISMATCH}/{@code NEED_REVIEW}；非识别类工单为 null）
     */
    public void recordHumanVerdict(String verdict, String reviewCode) {
        meterRegistry.counter("cabinet.recognition.human_verdict",
                "verdict", verdict == null ? "unknown" : verdict,
                "review_code", reviewCode == null || reviewCode.isBlank() ? "none" : reviewCode).increment();
    }

    /** 权限拒绝计数（realm=merchant / ops / unknown），与 AccessDeniedAudit 配合定位越权。 */
    public void recordPermissionDenied(String realm) {
        meterRegistry.counter("cabinet.permission.denied", "realm", realm != null ? realm : "unknown").increment();
    }
}
