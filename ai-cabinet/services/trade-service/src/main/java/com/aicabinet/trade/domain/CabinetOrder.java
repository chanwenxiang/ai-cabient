package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "cabinet_order")
public class CabinetOrder {

    @Id
    @Column(length = 32)
    private String orderId;

    @Column(nullable = false, length = 32)
    private String sessionId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String deviceId;

    @Column(nullable = false)
    private int totalAmountCents;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, length = 16)
    private String payChannel = "BALANCE";

    @Column(length = 64)
    private String payTradeNo;

    @Column(nullable = false)
    private boolean inventoryDeducted;

    private Instant refundedAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CabinetOrderLine> lines = new ArrayList<>();

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

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
    public boolean isInventoryDeducted() { return inventoryDeducted; }
    public void setInventoryDeducted(boolean inventoryDeducted) { this.inventoryDeducted = inventoryDeducted; }
    public Instant getRefundedAt() { return refundedAt; }
    public void setRefundedAt(Instant refundedAt) { this.refundedAt = refundedAt; }
    public List<CabinetOrderLine> getLines() { return lines; }
    public void setLines(List<CabinetOrderLine> lines) { this.lines = lines; }
    public Instant getCreatedAt() { return createdAt; }

    public void addLine(CabinetOrderLine line) {
        line.setOrder(this);
        lines.add(line);
    }
}
