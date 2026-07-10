package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "device_info")
public class DeviceInfo {

    @Id
    @Column(length = 64)
    private String deviceId;

    @Column(length = 128)
    private String deviceName;

    @Column(nullable = false, length = 32)
    private String deviceType;

    @Column(nullable = false, length = 16)
    private String onlineStatus;

    @Column(length = 32)
    private String appVersion;

    @Column(length = 32)
    private String firmwareVersion;

    private Double latitude;
    private Double longitude;

    @Column(length = 256)
    private String address;

    @Column(length = 32)
    private String merchantId;

    @Column(length = 64, name = "alert_contact_name")
    private String alertContactName;

    @Column(length = 32, name = "alert_contact_phone")
    private String alertContactPhone;

    @Column(name = "target_temp_c")
    private Integer targetTempC;

    @Column(name = "current_temp_c")
    private Integer currentTempC;

    private Instant tempReportedAt;

    @Column(length = 256, name = "ops_remark")
    private String opsRemark;

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getOnlineStatus() { return onlineStatus; }
    public void setOnlineStatus(String onlineStatus) { this.onlineStatus = onlineStatus; }
    public String getAppVersion() { return appVersion; }
    public void setAppVersion(String appVersion) { this.appVersion = appVersion; }
    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String firmwareVersion) { this.firmwareVersion = firmwareVersion; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getAlertContactName() { return alertContactName; }
    public void setAlertContactName(String alertContactName) { this.alertContactName = alertContactName; }
    public String getAlertContactPhone() { return alertContactPhone; }
    public void setAlertContactPhone(String alertContactPhone) { this.alertContactPhone = alertContactPhone; }
    public Integer getTargetTempC() { return targetTempC; }
    public void setTargetTempC(Integer targetTempC) { this.targetTempC = targetTempC; }
    public Integer getCurrentTempC() { return currentTempC; }
    public void setCurrentTempC(Integer currentTempC) { this.currentTempC = currentTempC; }
    public Instant getTempReportedAt() { return tempReportedAt; }
    public void setTempReportedAt(Instant tempReportedAt) { this.tempReportedAt = tempReportedAt; }
    public String getOpsRemark() { return opsRemark; }
    public void setOpsRemark(String opsRemark) { this.opsRemark = opsRemark; }
    public Instant getUpdatedAt() { return updatedAt; }
}
