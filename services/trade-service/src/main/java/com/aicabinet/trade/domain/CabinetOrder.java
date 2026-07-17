package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@TableName("cabinet_order")
public class CabinetOrder {

    @TableId(type = IdType.INPUT)
    private String orderId;

    private String sessionId;

    private Long userId;

    private String deviceId;

    private int totalAmountCents;

    private String status;

    private String payChannel = "BALANCE";

    private String payTradeNo;

    private String paymentOperationId;
    private Integer balanceBeforeCents;
    private Integer balanceAfterCents;

    private boolean inventoryDeducted;

    private Instant refundedAt;

    private Long couponId;

    private int couponDiscountCents;

    private int memberDiscountCents;

    private Long promotionId;

    @TableField(exist = false)
    private int originalAmountCents;

    @TableField(exist = false)
    private List<CabinetOrderLine> lines = new ArrayList<>();

    private Instant createdAt;

public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public int getTotalAmountCents() { return totalAmountCents; }
    public void setTotalAmountCents(int totalAmountCents) { this.totalAmountCents = totalAmountCents; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPayChannel() { return payChannel; }
    public void setPayChannel(String payChannel) { this.payChannel = payChannel; }
    public String getPayTradeNo() { return payTradeNo; }
    public void setPayTradeNo(String payTradeNo) { this.payTradeNo = payTradeNo; }
    public String getPaymentOperationId() { return paymentOperationId; }
    public void setPaymentOperationId(String paymentOperationId) { this.paymentOperationId = paymentOperationId; }
    public Integer getBalanceBeforeCents() { return balanceBeforeCents; }
    public void setBalanceBeforeCents(Integer balanceBeforeCents) { this.balanceBeforeCents = balanceBeforeCents; }
    public Integer getBalanceAfterCents() { return balanceAfterCents; }
    public void setBalanceAfterCents(Integer balanceAfterCents) { this.balanceAfterCents = balanceAfterCents; }
    public boolean isInventoryDeducted() { return inventoryDeducted; }
    public void setInventoryDeducted(boolean inventoryDeducted) { this.inventoryDeducted = inventoryDeducted; }
    public Instant getRefundedAt() { return refundedAt; }
    public void setRefundedAt(Instant refundedAt) { this.refundedAt = refundedAt; }
    public Long getCouponId() { return couponId; }
    public void setCouponId(Long couponId) { this.couponId = couponId; }
    public int getCouponDiscountCents() { return couponDiscountCents; }
    public void setCouponDiscountCents(int couponDiscountCents) { this.couponDiscountCents = couponDiscountCents; }
    public int getMemberDiscountCents() { return memberDiscountCents; }
    public void setMemberDiscountCents(int memberDiscountCents) { this.memberDiscountCents = memberDiscountCents; }
    public Long getPromotionId() { return promotionId; }
    public void setPromotionId(Long promotionId) { this.promotionId = promotionId; }
    public int getOriginalAmountCents() { return originalAmountCents; }
    public void setOriginalAmountCents(int originalAmountCents) { this.originalAmountCents = originalAmountCents; }
    public List<CabinetOrderLine> getLines() { return lines; }
    public void setLines(List<CabinetOrderLine> lines) { this.lines = lines; }
    public Instant getCreatedAt() { return createdAt; }

    public void addLine(CabinetOrderLine line) {
        line.setOrderId(this.orderId);
        lines.add(line);
    }
}
