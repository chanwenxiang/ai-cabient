package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("purchase_order")
public class PurchaseOrder {
    @TableId(type = IdType.AUTO)
    private Long purchaseOrderId;

    private String supplierId;
    private String warehouseId;
    private String status = "CREATED";
    private String refNo;
    private Long operatorId;
    private String notes;
    private Instant createdAt;
    private Instant receivedAt;

public Long getPurchaseOrderId() { return purchaseOrderId; }
    public String getSupplierId() { return supplierId; }
    public void setSupplierId(String supplierId) { this.supplierId = supplierId; }
    public String getWarehouseId() { return warehouseId; }
    public void setWarehouseId(String warehouseId) { this.warehouseId = warehouseId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRefNo() { return refNo; }
    public void setRefNo(String refNo) { this.refNo = refNo; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
}
