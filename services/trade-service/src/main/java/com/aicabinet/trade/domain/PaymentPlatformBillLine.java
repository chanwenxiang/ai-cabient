package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import java.time.Instant;

@TableName(value = "payment_platform_bill_line", autoResultMap = true)
public class PaymentPlatformBillLine {

    @TableId(type = IdType.AUTO)
    private Long lineId;

    private Long reconId;

    private String channel;

    private String platformTradeNo;

    private String merchantOrderNo;

    private long amountCents;

    private Instant tradeTime;

    private String tradeType;

    private boolean matched;

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String rawDetail;

    private Instant createdAt;

public Long getLineId() { return lineId; }
    public void setLineId(Long lineId) { this.lineId = lineId; }
    public Long getReconId() { return reconId; }
    public void setReconId(Long reconId) { this.reconId = reconId; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getPlatformTradeNo() { return platformTradeNo; }
    public void setPlatformTradeNo(String platformTradeNo) { this.platformTradeNo = platformTradeNo; }
    public String getMerchantOrderNo() { return merchantOrderNo; }
    public void setMerchantOrderNo(String merchantOrderNo) { this.merchantOrderNo = merchantOrderNo; }
    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }
    public Instant getTradeTime() { return tradeTime; }
    public void setTradeTime(Instant tradeTime) { this.tradeTime = tradeTime; }
    public String getTradeType() { return tradeType; }
    public void setTradeType(String tradeType) { this.tradeType = tradeType; }
    public boolean isMatched() { return matched; }
    public void setMatched(boolean matched) { this.matched = matched; }
    public String getRawDetail() { return rawDetail; }
    public void setRawDetail(String rawDetail) { this.rawDetail = rawDetail; }
    public Instant getCreatedAt() { return createdAt; }
}
