package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("recognition_compute_account")
public class RecognitionComputeAccount {
    @TableId(type = IdType.INPUT)
    private String merchantId;
    private long remaining;
    private long cumulative;
    private long used;
    private Instant updatedAt;

    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public long getRemaining() { return remaining; }
    public void setRemaining(long remaining) { this.remaining = remaining; }
    public long getCumulative() { return cumulative; }
    public void setCumulative(long cumulative) { this.cumulative = cumulative; }
    public long getUsed() { return used; }
    public void setUsed(long used) { this.used = used; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
