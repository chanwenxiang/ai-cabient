package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ops_exception")
public class OpsException {
    @Id @Column(length = 32) private String exceptionId;
    @Column(nullable = false, length = 32) private String exceptionType;
    @Column(nullable = false, length = 16) private String severity;
    @Column(nullable = false, length = 16) private String status;
    @Column(length = 64) private String deviceId;
    @Column(length = 32) private String sessionId;
    @Column(length = 32) private String orderId;
    private Long userId;
    @Column(nullable = false, length = 128) private String title;
    @Column(length = 1000) private String detail;
    private Long assigneeUserId;
    @Column(length = 1000) private String resolution;
    @Column(nullable = false, length = 160) private String dedupKey;
    @Column(nullable = false, updatable = false) private Instant createdAt;
    @Column(nullable = false) private Instant updatedAt;
    private Instant resolvedAt;

    @PrePersist void create() { Instant now = Instant.now(); createdAt = now; updatedAt = now; }
    @PreUpdate void update() { updatedAt = Instant.now(); }
    public String getExceptionId() { return exceptionId; } public void setExceptionId(String v) { exceptionId=v; }
    public String getExceptionType() { return exceptionType; } public void setExceptionType(String v) { exceptionType=v; }
    public String getSeverity() { return severity; } public void setSeverity(String v) { severity=v; }
    public String getStatus() { return status; } public void setStatus(String v) { status=v; }
    public String getDeviceId() { return deviceId; } public void setDeviceId(String v) { deviceId=v; }
    public String getSessionId() { return sessionId; } public void setSessionId(String v) { sessionId=v; }
    public String getOrderId() { return orderId; } public void setOrderId(String v) { orderId=v; }
    public Long getUserId() { return userId; } public void setUserId(Long v) { userId=v; }
    public String getTitle() { return title; } public void setTitle(String v) { title=v; }
    public String getDetail() { return detail; } public void setDetail(String v) { detail=v; }
    public Long getAssigneeUserId() { return assigneeUserId; } public void setAssigneeUserId(Long v) { assigneeUserId=v; }
    public String getResolution() { return resolution; } public void setResolution(String v) { resolution=v; }
    public String getDedupKey() { return dedupKey; } public void setDedupKey(String v) { dedupKey=v; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
    public Instant getResolvedAt() { return resolvedAt; } public void setResolvedAt(Instant v) { resolvedAt=v; }
}
