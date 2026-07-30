package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("platform_stored_value")
public class PlatformStoredValue {
    @TableId(type = IdType.INPUT)
    private String merchantId;
    private long balanceCents;
    private long warnThresholdCents;
    private String notifyPhone;
    private Instant updatedAt;

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public long getBalanceCents() { return balanceCents; }
    public void setBalanceCents(long balanceCents) { this.balanceCents = balanceCents; }
    public long getWarnThresholdCents() { return warnThresholdCents; }
    public void setWarnThresholdCents(long warnThresholdCents) { this.warnThresholdCents = warnThresholdCents; }
    public String getNotifyPhone() { return notifyPhone; }
    public void setNotifyPhone(String notifyPhone) { this.notifyPhone = notifyPhone; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
