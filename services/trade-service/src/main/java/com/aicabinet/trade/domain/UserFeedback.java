package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("user_feedback")
public class UserFeedback {

    @TableId(type = IdType.AUTO)
    private Long feedbackId;
    private Long userId;
    private String feedbackType;
    private String content;
    private String contactInfo;
    private String deviceId;
    private String sessionId;
    private String images;
    private Integer rating;
    private String status = "PENDING";
    private Long handlerId;
    private String reply;
    private Instant handledAt;
    private Instant createdAt;

    public Long getFeedbackId() { return feedbackId; }
    public void setFeedbackId(Long feedbackId) { this.feedbackId = feedbackId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getFeedbackType() { return feedbackType; }
    public void setFeedbackType(String feedbackType) { this.feedbackType = feedbackType; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getContactInfo() { return contactInfo; }
    public void setContactInfo(String contactInfo) { this.contactInfo = contactInfo; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getImages() { return images; }
    public void setImages(String images) { this.images = images; }
    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getHandlerId() { return handlerId; }
    public void setHandlerId(Long handlerId) { this.handlerId = handlerId; }
    public String getReply() { return reply; }
    public void setReply(String reply) { this.reply = reply; }
    public Instant getHandledAt() { return handledAt; }
    public void setHandledAt(Instant handledAt) { this.handledAt = handledAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
