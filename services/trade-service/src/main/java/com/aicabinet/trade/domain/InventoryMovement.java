package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("inventory_movement")
public class InventoryMovement {

    @TableId(type = IdType.AUTO)
    private Long movementId;

    private String deviceId;

    private String skuId;

    private String batchNo;

    private String movementType;

    private int deltaQty;

    private String refType;

    private String refId;

    private Long operatorId;

    private Instant createdAt;

public Long getMovementId() { return movementId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getSkuId() { return skuId; }
    public void setSkuId(String skuId) { this.skuId = skuId; }
    public String getBatchNo() { return batchNo; }
    public void setBatchNo(String batchNo) { this.batchNo = batchNo; }
    public String getMovementType() { return movementType; }
    public void setMovementType(String movementType) { this.movementType = movementType; }
    public int getDeltaQty() { return deltaQty; }
    public void setDeltaQty(int deltaQty) { this.deltaQty = deltaQty; }
    public String getRefType() { return refType; }
    public void setRefType(String refType) { this.refType = refType; }
    public String getRefId() { return refId; }
    public void setRefId(String refId) { this.refId = refId; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long operatorId) { this.operatorId = operatorId; }
    public Instant getCreatedAt() { return createdAt; }
}
