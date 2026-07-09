package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "purchase_order_line")
public class PurchaseOrderLine {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long lineId;
    @Column(nullable = false)
    private Long purchaseOrderId;
    @Column(nullable = false, length = 64)
    private String skuId;
    @Column(nullable = false, length = 64)
    private String batchNo;
    private LocalDate productionDate;
    @Column(nullable = false)
    private LocalDate expiryDate;
    @Column(nullable = false)
    private int orderedQty;
    @Column(nullable = false)
    private int receivedQty;
    @Column(nullable = false)
    private int unitCostCents;
    @Column(nullable = false, length = 16)
    private String qualityStatus = "PENDING";
    @Column(length = 256)
    private String qualityNote;
    @Column(nullable = false)
    private int rejectedQty;

    public Long getLineId() { return lineId; }
    public Long getPurchaseOrderId() { return purchaseOrderId; }
    public void setPurchaseOrderId(Long purchaseOrderId) { this.purchaseOrderId = purchaseOrderId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public LocalDate getProductionDate() { return productionDate; }
    public void setProductionDate(LocalDate productionDate) { this.productionDate = productionDate; }
    public LocalDate getExpiryDate() { return expiryDate; }
    public void setExpiryDate(LocalDate expiryDate) { this.expiryDate = expiryDate; }
    public int getOrderedQty() { return orderedQty; }
    public void setOrderedQty(int orderedQty) { this.orderedQty = orderedQty; }
    public int getReceivedQty() { return receivedQty; }
    public void setReceivedQty(int receivedQty) { this.receivedQty = receivedQty; }
    public int getUnitCostCents() { return unitCostCents; }
    public void setUnitCostCents(int unitCostCents) { this.unitCostCents = unitCostCents; }
    public String getQualityStatus() { return qualityStatus; }
    public void setQualityStatus(String qualityStatus) { this.qualityStatus = qualityStatus; }
    public String getQualityNote() { return qualityNote; }
    public void setQualityNote(String qualityNote) { this.qualityNote = qualityNote; }
    public int getRejectedQty() { return rejectedQty; }
    public void setRejectedQty(int rejectedQty) { this.rejectedQty = rejectedQty; }
}
