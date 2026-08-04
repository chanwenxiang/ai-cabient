package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("repair_ticket")
public class RepairTicket {

    @TableId(type = IdType.AUTO)
    private Long ticketId;
    private String deviceId;
    private String title;
    private String faultType;
    private String status;
    private String assignee;
    private String priority;
    private String remark;
    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant closedAt;

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getFaultType() { return faultType; }
    public void setFaultType(String faultType) { this.faultType = faultType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAssignee() { return assignee; }
    public void setAssignee(String assignee) { this.assignee = assignee; }
    public String getPriority() { return priority; }
    public void setPriority(String priority) { this.priority = priority; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public Instant getClosedAt() { return closedAt; }
    public void setClosedAt(Instant closedAt) { this.closedAt = closedAt; }
}
