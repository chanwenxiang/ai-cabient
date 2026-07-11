package com.aicabinet.trade.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "payment_operation")
public class PaymentOperation {

    @Id
    @Column(length = 64)
    private String operationId;

    @Column(nullable = false, length = 32)
    private String orderId;

    @Column(nullable = false, length = 24)
    private String operationType;

    @Column(nullable = false)
    private int amountCents;

    @Column(nullable = false, length = 16)
    private String channel;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, length = 128, unique = true)
    private String idempotencyKey;

    @Column(length = 64)
    private String gatewayTradeNo;

    @Column(length = 128)
    private String reason;

    private Long userId;

    private Integer balanceBeforeCents;

    private Integer balanceAfterCents;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public String getOperationId() { return operationId; }
    public void setOperationId(String operationId) { this.operationId = operationId; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getOperationType() { return operationType; }
    public void setOperationType(String operationType) { this.operationType = operationType; }
    public int getAmountCents() { return amountCents; }
    public void setAmountCents(int amountCents) { this.amountCents = amountCents; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getGatewayTradeNo() { return gatewayTradeNo; }
    public void setGatewayTradeNo(String gatewayTradeNo) { this.gatewayTradeNo = gatewayTradeNo; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Integer getBalanceBeforeCents() { return balanceBeforeCents; }
    public void setBalanceBeforeCents(Integer balanceBeforeCents) { this.balanceBeforeCents = balanceBeforeCents; }
    public Integer getBalanceAfterCents() { return balanceAfterCents; }
    public void setBalanceAfterCents(Integer balanceAfterCents) { this.balanceAfterCents = balanceAfterCents; }
}
