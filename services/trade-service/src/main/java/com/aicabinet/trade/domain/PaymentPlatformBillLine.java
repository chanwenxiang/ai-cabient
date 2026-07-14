package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "payment_platform_bill_line")
public class PaymentPlatformBillLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long lineId;

    private Long reconId;

    @Column(nullable = false, length = 16)
    private String channel;

    @Column(nullable = false, length = 64)
    private String platformTradeNo;

    @Column(length = 64)
    private String merchantOrderNo;

    @Column(nullable = false)
    private long amountCents;

    private Instant tradeTime;

    @Column(length = 32)
    private String tradeType;

    @Column(nullable = false)
    private boolean matched;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String rawDetail;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

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
