package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "sla_daily_snapshot")
public class SlaDailySnapshot {

    @Id
    private LocalDate snapshotDate;

    @Column(nullable = false)
    private int doorOpenAttempts;

    @Column(nullable = false)
    private int doorOpenSuccess;

    private Float doorSuccessRate;

    @Column(name = "avg_recognize_ms")
    private Long avgRecognizeMs;

    @Column(name = "p95_recognize_ms")
    private Long p95RecognizeMs;

    @Column(nullable = false)
    private int deviceTotal;

    @Column(nullable = false)
    private int deviceOnlinePeak;

    private Float deviceOnlineRate;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public LocalDate getSnapshotDate() { return snapshotDate; }
    public void setSnapshotDate(LocalDate snapshotDate) { this.snapshotDate = snapshotDate; }
    public int getDoorOpenAttempts() { return doorOpenAttempts; }
    public void setDoorOpenAttempts(int doorOpenAttempts) { this.doorOpenAttempts = doorOpenAttempts; }
    public int getDoorOpenSuccess() { return doorOpenSuccess; }
    public void setDoorOpenSuccess(int doorOpenSuccess) { this.doorOpenSuccess = doorOpenSuccess; }
    public Float getDoorSuccessRate() { return doorSuccessRate; }
    public void setDoorSuccessRate(Float doorSuccessRate) { this.doorSuccessRate = doorSuccessRate; }
    public Long getAvgRecognizeMs() { return avgRecognizeMs; }
    public void setAvgRecognizeMs(Long avgRecognizeMs) { this.avgRecognizeMs = avgRecognizeMs; }
    public Long getP95RecognizeMs() { return p95RecognizeMs; }
    public void setP95RecognizeMs(Long p95RecognizeMs) { this.p95RecognizeMs = p95RecognizeMs; }
    public int getDeviceTotal() { return deviceTotal; }
    public void setDeviceTotal(int deviceTotal) { this.deviceTotal = deviceTotal; }
    public int getDeviceOnlinePeak() { return deviceOnlinePeak; }
    public void setDeviceOnlinePeak(int deviceOnlinePeak) { this.deviceOnlinePeak = deviceOnlinePeak; }
    public Float getDeviceOnlineRate() { return deviceOnlineRate; }
    public void setDeviceOnlineRate(Float deviceOnlineRate) { this.deviceOnlineRate = deviceOnlineRate; }
    public Instant getCreatedAt() { return createdAt; }
}
