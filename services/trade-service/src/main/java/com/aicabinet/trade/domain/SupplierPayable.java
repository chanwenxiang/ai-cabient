package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 供应商应付账款：每张采购单一条，收货累加金额，退货冲减，付款核销。
 * 状态：UNPAID / PARTIAL / PAID / CLOSED（全额退货冲销）。
 */
@TableName("supplier_payable")
public class SupplierPayable {

    @TableId(type = IdType.AUTO)
    private Long payableId;
    private String supplierId;
    private Long purchaseOrderId;
    private String warehouseId;
    private long amountCents;
    private long paidAmountCents;
    private String status = "UNPAID";
    private LocalDate dueDate;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant paidAt;

    public Long getPayableId() { return payableId; }
    public void setPayableId(Long payableId) { this.payableId = payableId; }
    public String getSupplierId() { return supplierId; }
    public void setSupplierId(String supplierId) { this.supplierId = supplierId; }
    public Long getPurchaseOrderId() { return purchaseOrderId; }
    public void setPurchaseOrderId(Long purchaseOrderId) { this.purchaseOrderId = purchaseOrderId; }
    public String getWarehouseId() { return warehouseId; }
    public void setWarehouseId(String warehouseId) { this.warehouseId = warehouseId; }
    public long getAmountCents() { return amountCents; }
    public void setAmountCents(long amountCents) { this.amountCents = amountCents; }
    public long getPaidAmountCents() { return paidAmountCents; }
    public void setPaidAmountCents(long paidAmountCents) { this.paidAmountCents = paidAmountCents; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
}
