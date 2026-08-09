package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

/** 仓库货位。 */
@TableName("warehouse_bin")
public class WarehouseBin {

    @TableId(type = IdType.AUTO)
    private Long binId;
    private String warehouseId;
    private String binCode;
    private String binName;
    private String status = "ACTIVE";
    private Instant createdAt;

    public Long getBinId() { return binId; }
    public void setBinId(Long binId) { this.binId = binId; }
    public String getWarehouseId() { return warehouseId; }
    public void setWarehouseId(String warehouseId) { this.warehouseId = warehouseId; }
    public String getBinCode() { return binCode; }
    public void setBinCode(String binCode) { this.binCode = binCode; }
    public String getBinName() { return binName; }
    public void setBinName(String binName) { this.binName = binName; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
