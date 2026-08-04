package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import java.time.LocalDate;

@TableName("order_revenue_split")
public class OrderRevenueSplit {

    @TableId(type = IdType.INPUT)
    private String splitId;

    private String orderId;

    private String merchantId;

    private String deviceId;

    private long grossCents;

    private long platformCents;

    private long merchantCents;

    private String status = "ACCRUED";

    private String wechatOutOrderNo;

    private String wechatTransactionId;

    private String failureReason;
    private String settlementBatchNo;
    private LocalDate settleAfter;
    private Instant settledAt;

    private Instant createdAt;

public String getSplitId() { return splitId; }
    public void setSplitId(String splitId) { this.splitId = splitId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public long getGrossCents() { return grossCents; }
    public void setGrossCents(long grossCents) { this.grossCents = grossCents; }
    public long getPlatformCents() { return platformCents; }
    public void setPlatformCents(long platformCents) { this.platformCents = platformCents; }
    public long getMerchantCents() { return merchantCents; }
    public void setMerchantCents(long merchantCents) { this.merchantCents = merchantCents; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getWechatOutOrderNo() { return wechatOutOrderNo; }
    public void setWechatOutOrderNo(String wechatOutOrderNo) { this.wechatOutOrderNo = wechatOutOrderNo; }
    public String getWechatTransactionId() { return wechatTransactionId; }
    public void setWechatTransactionId(String wechatTransactionId) { this.wechatTransactionId = wechatTransactionId; }
    public String getFailureReason() { return failureReason; }
    public void setFailureReason(String failureReason) { this.failureReason = failureReason; }
    public String getSettlementBatchNo() { return settlementBatchNo; }
    public void setSettlementBatchNo(String settlementBatchNo) { this.settlementBatchNo = settlementBatchNo; }
    public LocalDate getSettleAfter() { return settleAfter; }
    public void setSettleAfter(LocalDate settleAfter) { this.settleAfter = settleAfter; }
    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }
    public Instant getCreatedAt() { return createdAt; }
}
