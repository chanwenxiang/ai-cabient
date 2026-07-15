package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import java.time.LocalDate;

@TableName("sla_daily_snapshot")
public class SlaDailySnapshot {

    @TableId(type = IdType.INPUT)
    private LocalDate snapshotDate;

    private int doorOpenAttempts;

    private int doorOpenSuccess;

    private Float doorSuccessRate;

    private Long avgRecognizeMs;

    private Long p95RecognizeMs;

    private int deviceTotal;

    private int deviceOnlinePeak;

    private Float deviceOnlineRate;

    private Instant createdAt;

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
