package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

@TableName("device_availability_kpi_daily")
public class DeviceAvailabilityKpiDaily {

    @TableId(type = IdType.INPUT)
    private LocalDate kpiDate;

    private Integer deviceTotal;
    private Integer offlineEvents;
    private Integer autoLockCount;
    private Integer autoUnlockCount;
    private Integer manualUnlockCount;
    private Double avgLockHours;
    private Double avgRecoverHours;
    private Double manualInterventionRate;
    private Instant createdAt;

    public LocalDate getKpiDate() { return kpiDate; }
    public void setKpiDate(LocalDate kpiDate) { this.kpiDate = kpiDate; }
    public Integer getDeviceTotal() { return deviceTotal; }
    public void setDeviceTotal(Integer deviceTotal) { this.deviceTotal = deviceTotal; }
    public Integer getOfflineEvents() { return offlineEvents; }
    public void setOfflineEvents(Integer offlineEvents) { this.offlineEvents = offlineEvents; }
    public Integer getAutoLockCount() { return autoLockCount; }
    public void setAutoLockCount(Integer autoLockCount) { this.autoLockCount = autoLockCount; }
    public Integer getAutoUnlockCount() { return autoUnlockCount; }
    public void setAutoUnlockCount(Integer autoUnlockCount) { this.autoUnlockCount = autoUnlockCount; }
    public Integer getManualUnlockCount() { return manualUnlockCount; }
    public void setManualUnlockCount(Integer manualUnlockCount) { this.manualUnlockCount = manualUnlockCount; }
    public Double getAvgLockHours() { return avgLockHours; }
    public void setAvgLockHours(Double avgLockHours) { this.avgLockHours = avgLockHours; }
    public Double getAvgRecoverHours() { return avgRecoverHours; }
    public void setAvgRecoverHours(Double avgRecoverHours) { this.avgRecoverHours = avgRecoverHours; }
    public Double getManualInterventionRate() { return manualInterventionRate; }
    public void setManualInterventionRate(Double manualInterventionRate) {
        this.manualInterventionRate = manualInterventionRate;
    }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
