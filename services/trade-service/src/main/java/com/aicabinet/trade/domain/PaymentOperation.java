package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("payment_operation")
public class PaymentOperation {

    @TableId(type = IdType.INPUT)
    private String operationId;

    private String orderId;

    private String operationType;

    private int amountCents;

    private String channel;

    private String status;

    private String idempotencyKey;

    private String gatewayTradeNo;

    private String reason;

    private Long userId;

    private Integer balanceBeforeCents;

    private Integer balanceAfterCents;

    private Instant createdAt;

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
