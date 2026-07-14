package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "warehouse_outbound_line")
public class WarehouseOutboundLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long lineId;

    @Column(nullable = false)
    private Long outboundId;

    @Column(length = 64)
    private String deviceId;

    @Column(nullable = false, length = 64)
    private String skuId;

    @Column(nullable = false, length = 64)
    private String batchNo;

    private LocalDate expiryDate;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private boolean picked;
    @Column(nullable = false, length = 16)
    private String handoverStatus = "PENDING";

    public Long getLineId() { return lineId; }
    public Long getOutboundId() { return outboundId; }
    public void setOutboundId(Long outboundId) { this.outboundId = outboundId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public boolean isPicked() { return picked; }
    public void setPicked(boolean picked) { this.picked = picked; }
    public String getHandoverStatus() { return handoverStatus; }
    public void setHandoverStatus(String handoverStatus) { this.handoverStatus = handoverStatus; }
}
