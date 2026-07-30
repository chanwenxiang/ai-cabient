package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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

    /** AUTO_REFUND | DISPUTE_ONLY | null=继承全局 */
    private String refundPolicy;

    /** 营业锁机：禁止消费者开门 */
    private Boolean salesLocked;

    /** 价格锁：禁止改价 */
    private Boolean priceLocked;

    /** 禁止改 SKU / 货道商品 */
    private Boolean skuEditForbidden;

    /** 禁售（策略层，通常伴随营业锁机） */
    private Boolean saleForbidden;

    @TableField(fill = FieldFill.INSERT_UPDATE)
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
    public String getRefundPolicy() { return refundPolicy; }
    public void setRefundPolicy(String refundPolicy) { this.refundPolicy = refundPolicy; }
    public Boolean getSalesLocked() { return salesLocked; }
    public void setSalesLocked(Boolean salesLocked) { this.salesLocked = salesLocked; }
    public boolean salesLockedEnabled() { return Boolean.TRUE.equals(salesLocked); }
    public Boolean getPriceLocked() { return priceLocked; }
    public void setPriceLocked(Boolean priceLocked) { this.priceLocked = priceLocked; }
    public Boolean getSkuEditForbidden() { return skuEditForbidden; }
    public void setSkuEditForbidden(Boolean skuEditForbidden) { this.skuEditForbidden = skuEditForbidden; }
    public Boolean getSaleForbidden() { return saleForbidden; }
    public void setSaleForbidden(Boolean saleForbidden) { this.saleForbidden = saleForbidden; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void markHeartbeatReceived() { updatedAt = Instant.now(); }
}
