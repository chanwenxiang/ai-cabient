package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("recharge_order")
public class RechargeOrder {

    @TableId(type = IdType.INPUT)
    private String orderId;

    private Long userId;

    private int amountCents;

    private String channel;

    private String status;

    private String idempotencyKey;

    private String paymentOperationId;

    private String wxPrepayId;

    private String wxTransactionId;

    private String alipayTradeNo;

    private Instant createdAt;

    private Instant paidAt;
    private Instant refundedAt;

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
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public String getPaymentOperationId() { return paymentOperationId; }
    public void setPaymentOperationId(String paymentOperationId) { this.paymentOperationId = paymentOperationId; }
    public String getWxPrepayId() { return wxPrepayId; }
    public void setWxPrepayId(String wxPrepayId) { this.wxPrepayId = wxPrepayId; }
    public String getWxTransactionId() { return wxTransactionId; }
    public void setWxTransactionId(String wxTransactionId) { this.wxTransactionId = wxTransactionId; }
    public String getAlipayTradeNo() { return alipayTradeNo; }
    public void setAlipayTradeNo(String alipayTradeNo) { this.alipayTradeNo = alipayTradeNo; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public Instant getRefundedAt() { return refundedAt; }
    public void setRefundedAt(Instant refundedAt) { this.refundedAt = refundedAt; }
}
