package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "announcement")
public class Announcement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long announceId;

    @Column(nullable = false, length = 256)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(nullable = false, length = 16)
    private String announceType = "SYSTEM";

    @Column(nullable = false, length = 32)
    private String targetScope = "ALL";

    @Column(length = 64)
    private String targetDevice;

    @Column(nullable = false, length = 8)
    private String priority = "NORMAL";

    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    private Instant publishAt;
    private Instant expireAt;
    private Long operatorId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    protected void onCreate() { createdAt = Instant.now(); updatedAt = Instant.now(); }
    @PreUpdate
    protected void onUpdate() { updatedAt = Instant.now(); }

    public Long getAnnounceId() { return announceId; }
    public void setAnnounceId(Long v) { this.announceId = v; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public String getAnnounceType() { return announceType; }
    public void setAnnounceType(String v) { this.announceType = v; }
    public String getTargetScope() { return targetScope; }
    public void setTargetScope(String v) { this.targetScope = v; }
    public String getTargetDevice() { return targetDevice; }
    public void setTargetDevice(String v) { this.targetDevice = v; }
    public String getPriority() { return priority; }
    public void setPriority(String v) { this.priority = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }
    public Instant getPublishAt() { return publishAt; }
    public void setPublishAt(Instant v) { this.publishAt = v; }
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant v) { this.expireAt = v; }
    public Long getOperatorId() { return operatorId; }
    public void setOperatorId(Long v) { this.operatorId = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
