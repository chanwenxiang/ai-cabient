package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("ops_exception")
public class OpsException {
    @TableId(type = IdType.INPUT)
    private String exceptionId;
    private String exceptionType;
    private String severity;
    private String status;
    private String deviceId;
    private String sessionId;
    private String orderId;
    private Long userId;
    private String title;
    private String detail;
    private Long assigneeUserId;
    private String resolution;
    private String dedupKey;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant resolvedAt;

    public String getExceptionId() { return exceptionId; }
    public void setExceptionId(String v) { exceptionId = v; }
    public String getExceptionType() { return exceptionType; }
    public void setExceptionType(String v) { exceptionType = v; }
    public String getSeverity() { return severity; }
    public void setSeverity(String v) { severity = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String v) { deviceId = v; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { sessionId = v; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String v) { orderId = v; }
    public Long getUserId() { return userId; }
    public void setUserId(Long v) { userId = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { title = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { detail = v; }
    public Long getAssigneeUserId() { return assigneeUserId; }
    public void setAssigneeUserId(Long v) { assigneeUserId = v; }
    public String getResolution() { return resolution; }
    public void setResolution(String v) { resolution = v; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String v) { dedupKey = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant v) { resolvedAt = v; }
}
