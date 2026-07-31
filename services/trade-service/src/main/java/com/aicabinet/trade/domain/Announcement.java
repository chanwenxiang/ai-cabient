package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("announcement")
public class Announcement {

    @TableId(type = IdType.AUTO)
    private Long announceId;

    private String title;

    private String content;

    private String announceType = "SYSTEM";

    private String targetScope = "ALL";

    private String targetDevice;

    private String priority = "NORMAL";

    private String status = "DRAFT";

    private Instant publishAt;
    private Instant expireAt;
    private Long operatorId;

    private Instant createdAt;

    private Instant updatedAt;

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
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
}
