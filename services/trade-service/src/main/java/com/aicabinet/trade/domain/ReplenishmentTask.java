package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("replenishment_task")
public class ReplenishmentTask {

    @TableId(type = IdType.AUTO)
    private Long taskId;

    private Long routeId;

    private String deviceId;

    private Long assigneeUserId;

    private String status = "PENDING";

    private String notes;

    private Instant completedAt;

    private Long outboundId;

    private Long requestId;

    private Instant checkInAt;

    private Double checkInLat;

    private Double checkInLng;

    private Instant createdAt;

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getRouteId() { return routeId; }
    public void setRouteId(Long routeId) { this.routeId = routeId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public Long getAssigneeUserId() { return assigneeUserId; }
    public void setAssigneeUserId(Long assigneeUserId) { this.assigneeUserId = assigneeUserId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
    public Long getOutboundId() { return outboundId; }
    public void setOutboundId(Long outboundId) { this.outboundId = outboundId; }
    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }
    public Instant getCheckInAt() { return checkInAt; }
    public void setCheckInAt(Instant checkInAt) { this.checkInAt = checkInAt; }
    public Double getCheckInLat() { return checkInLat; }
    public void setCheckInLat(Double checkInLat) { this.checkInLat = checkInLat; }
    public Double getCheckInLng() { return checkInLng; }
    public void setCheckInLng(Double checkInLng) { this.checkInLng = checkInLng; }
}
