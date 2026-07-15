package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDate;

@TableName("purchase_order_line")
public class PurchaseOrderLine {
    @TableId(type = IdType.AUTO)
    private Long lineId;

    private Long purchaseOrderId;
    private String skuId;
    private String batchNo;
    private LocalDate productionDate;
    private LocalDate expiryDate;
    private int orderedQty;
    private int receivedQty;
    private int unitCostCents;
    private String qualityStatus = "PENDING";
    private String qualityNote;
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
