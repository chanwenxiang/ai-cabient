package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("device_info")
public class DeviceInfo {

    @TableId(type = IdType.INPUT)
    private String deviceId;

    private String deviceName;

    private String deviceType;

    private String onlineStatus;

    private String appVersion;

    private String firmwareVersion;

    private Double latitude;
    private Double longitude;

    private String address;

    private String merchantId;

    private String alertContactName;

    private String alertContactPhone;

    private Integer targetTempC;

    private Integer currentTempC;

    private Instant tempReportedAt;

    private String opsRemark;

    private Instant updatedAt;

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
    public void markHeartbeatReceived() { updatedAt = Instant.now(); }
}
