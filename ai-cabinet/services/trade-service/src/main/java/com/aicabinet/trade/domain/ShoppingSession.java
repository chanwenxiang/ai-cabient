package com.aicabinet.trade.domain;

import com.aicabinet.common.enums.SessionState;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "shopping_session")
public class ShoppingSession {

    @Id
    @Column(length = 32)
    private String sessionId;

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false, length = 64)
    private String deviceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private SessionState state;

    private Instant openTime;
    private Instant closeTime;

    @Column(length = 32)
    private String orderId;

    @Column(length = 256)
    private String failReason;

    @Column(length = 64)
    private String recognitionTaskId;

    @Column(length = 512)
    private String videoUri;

    @Column(nullable = false, length = 24)
    private String uploadStatus = "NONE";

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "video_clips", columnDefinition = "jsonb")
    private String videoClips;

    @Column(nullable = false, length = 16)
    private String cameraFusionMode = "SINGLE";

    @Column(unique = true, length = 64)
    private String idempotencyKey;

    private Long replenishmentTaskId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "gravity_deltas", columnDefinition = "jsonb")
    private String gravityDeltas;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public SessionState getState() { return state; }
    public void setState(SessionState state) { this.state = state; }
    public Instant getOpenTime() { return openTime; }
    public void setOpenTime(Instant openTime) { this.openTime = openTime; }
    public Instant getCloseTime() { return closeTime; }
    public void setCloseTime(Instant closeTime) { this.closeTime = closeTime; }
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    public String getFailReason() { return failReason; }
    public void setFailReason(String failReason) { this.failReason = failReason; }
    public String getRecognitionTaskId() { return recognitionTaskId; }
    public void setRecognitionTaskId(String recognitionTaskId) { this.recognitionTaskId = recognitionTaskId; }
    public String getVideoUri() { return videoUri; }
    public void setVideoUri(String videoUri) { this.videoUri = videoUri; }
    public String getUploadStatus() { return uploadStatus; }
    public void setUploadStatus(String uploadStatus) { this.uploadStatus = uploadStatus; }
    public String getVideoClips() { return videoClips; }
    public void setVideoClips(String videoClips) { this.videoClips = videoClips; }
    public String getCameraFusionMode() { return cameraFusionMode; }
    public void setCameraFusionMode(String cameraFusionMode) { this.cameraFusionMode = cameraFusionMode; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    public Long getReplenishmentTaskId() { return replenishmentTaskId; }
    public void setReplenishmentTaskId(Long replenishmentTaskId) { this.replenishmentTaskId = replenishmentTaskId; }
    public String getGravityDeltas() { return gravityDeltas; }
    public void setGravityDeltas(String gravityDeltas) { this.gravityDeltas = gravityDeltas; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
