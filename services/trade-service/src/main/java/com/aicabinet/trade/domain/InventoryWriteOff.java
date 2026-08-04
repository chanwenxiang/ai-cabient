package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("inventory_write_off")
public class InventoryWriteOff {

    @TableId(type = IdType.AUTO)
    private Long writeOffId;

    private String deviceId;

    private String skuId;

    private String batchNo;

    private int quantity;

    private String reason;

    private Integer costCents;

    private Long operatorId;

    private Instant createdAt;

public Long getWriteOffId() { return writeOffId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Integer getCostCents() { return costCents; }
    public void setCostCents(Integer costCents) { this.costCents = costCents; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public Instant getCreatedAt() { return createdAt; }
}
