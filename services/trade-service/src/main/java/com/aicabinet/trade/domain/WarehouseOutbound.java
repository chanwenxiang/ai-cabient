package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("warehouse_outbound")
public class WarehouseOutbound {

    @TableId(type = IdType.AUTO)
    private Long outboundId;

    private String warehouseId;

    private Long routeId;

    private String status = "DRAFT";

    private Long assigneeUserId;

    private String notes;

    private Instant createdAt;

    private Instant shippedAt;
    private String handoverStatus = "PENDING";
    private Long handoverOperatorId;
    private Instant handedOverAt;

public Long getOutboundId() { return outboundId; }
    public String getWarehouseId() { return warehouseId; }
    public void setWarehouseId(String warehouseId) { this.warehouseId = warehouseId; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getAssigneeUserId() { return assigneeUserId; }
    public void setAssigneeUserId(Long assigneeUserId) { this.assigneeUserId = assigneeUserId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getShippedAt() { return shippedAt; }
    public void setShippedAt(Instant shippedAt) { this.shippedAt = shippedAt; }
    public String getHandoverStatus() { return handoverStatus; }
    public void setHandoverStatus(String handoverStatus) { this.handoverStatus = handoverStatus; }
    public Long getHandoverOperatorId() { return handoverOperatorId; }
    public void setHandoverOperatorId(Long handoverOperatorId) { this.handoverOperatorId = handoverOperatorId; }
    public Instant getHandedOverAt() { return handedOverAt; }
    public void setHandedOverAt(Instant handedOverAt) { this.handedOverAt = handedOverAt; }
}
