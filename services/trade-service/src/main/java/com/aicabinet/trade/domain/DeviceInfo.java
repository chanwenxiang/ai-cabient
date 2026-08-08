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

    /** 自增数字 ID，列表展示用；业务主键仍为 deviceId */
    private Long id;

    private String deviceName;

    private String deviceType;

    private String onlineStatus;

    /** 最近一次恢复在线的时间（离线时置空），用于稳定在线自动解锁 */
    private Instant onlineSince;

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

    /** INBOUND|IDLE|DEPLOYED|RETURNING|RETIRED */
    private String lifecycleStatus;

    private String imei;

    private String assetOwner;

    /** SELF|FRANCHISE|CONSIGN */
    private String coopMode;

    private Long depositCents;

    private Long dataFeeCents;

    private String opsTags;

    private String routeCode;

    private Instant deployedAt;

    private String lifecycleRemark;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getOnlineStatus() { return onlineStatus; }
    public void setOnlineStatus(String onlineStatus) { this.onlineStatus = onlineStatus; }
    public Instant getOnlineSince() { return onlineSince; }
    public void setOnlineSince(Instant onlineSince) { this.onlineSince = onlineSince; }
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
    public String getLifecycleStatus() { return lifecycleStatus; }
    public void setLifecycleStatus(String lifecycleStatus) { this.lifecycleStatus = lifecycleStatus; }
    public String getImei() { return imei; }
    public void setImei(String imei) { this.imei = imei; }
    public String getAssetOwner() { return assetOwner; }
    public void setAssetOwner(String assetOwner) { this.assetOwner = assetOwner; }
    public String getCoopMode() { return coopMode; }
    public void setCoopMode(String coopMode) { this.coopMode = coopMode; }
    public Long getDepositCents() { return depositCents; }
    public void setDepositCents(Long depositCents) { this.depositCents = depositCents; }
    public Long getDataFeeCents() { return dataFeeCents; }
    public void setDataFeeCents(Long dataFeeCents) { this.dataFeeCents = dataFeeCents; }
    public String getOpsTags() { return opsTags; }
    public void setOpsTags(String opsTags) { this.opsTags = opsTags; }
    public String getRouteCode() { return routeCode; }
    public void setRouteCode(String routeCode) { this.routeCode = routeCode; }
    public Instant getDeployedAt() { return deployedAt; }
    public void setDeployedAt(Instant deployedAt) { this.deployedAt = deployedAt; }
    public String getLifecycleRemark() { return lifecycleRemark; }
    public void setLifecycleRemark(String lifecycleRemark) { this.lifecycleRemark = lifecycleRemark; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public void markHeartbeatReceived() { updatedAt = Instant.now(); }
}
