package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "warehouse_inbound_line")
public class WarehouseInboundLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long lineId;

    @Column(nullable = false)
    private Long inboundId;

    @Column(nullable = false, length = 64)
    private String skuId;

    @Column(nullable = false, length = 64)
    private String batchNo;

    private LocalDate productionDate;

    @Column(nullable = false)
    private LocalDate expiryDate;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int unitCostCents;

    public Long getLineId() { return lineId; }
    public Long getInboundId() { return inboundId; }
    public void setInboundId(Long inboundId) { this.inboundId = inboundId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public LocalDate getProductionDate() { return productionDate; }
    public void setProductionDate(LocalDate productionDate) { this.productionDate = productionDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public int getUnitCostCents() { return unitCostCents; }
    public void setUnitCostCents(int unitCostCents) { this.unitCostCents = unitCostCents; }
}
