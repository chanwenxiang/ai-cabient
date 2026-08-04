package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import java.time.LocalDate;

@TableName("replenishment_task_line")
public class ReplenishmentTaskLine {

    @TableId(type = IdType.AUTO)
    private Long lineId;

    private Long taskId;

    private String lineType;

    private String skuId;

    private String batchNo;

    private LocalDate productionDate;

    private LocalDate expiryDate;

    private int quantity;

    private String slotId;

    private boolean applied;

    private Instant createdAt;

public Long getLineId() { return lineId; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getLineType() { return lineType; }
    public void setLineType(String lineType) { this.lineType = lineType; }
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
    public String getSlotId() { return slotId; }
    public void setSlotId(String slotId) { this.slotId = slotId; }
    public boolean isApplied() { return applied; }
    public void setApplied(boolean applied) { this.applied = applied; }
    public Instant getCreatedAt() { return createdAt; }
}
