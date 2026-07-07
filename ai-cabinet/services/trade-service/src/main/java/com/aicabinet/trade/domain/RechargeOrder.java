package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "recharge_order")
public class RechargeOrder {

    @Id
    @Column(length = 32)
    private String orderId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int amountCents;

    @Column(nullable = false, length = 16)
    private String channel;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = 64)
    private String wxPrepayId;

    @Column(length = 64)
    private String wxTransactionId;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant paidAt;
    private Instant refundedAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public int getAmountCents() { return amountCents; }
    public void setAmountCents(int amountCents) { this.amountCents = amountCents; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getWxPrepayId() { return wxPrepayId; }
    public void setWxPrepayId(String wxPrepayId) { this.wxPrepayId = wxPrepayId; }
    public String getWxTransactionId() { return wxTransactionId; }
    public void setWxTransactionId(String wxTransactionId) { this.wxTransactionId = wxTransactionId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public Instant getRefundedAt() { return refundedAt; }
    public void setRefundedAt(Instant refundedAt) { this.refundedAt = refundedAt; }
}
