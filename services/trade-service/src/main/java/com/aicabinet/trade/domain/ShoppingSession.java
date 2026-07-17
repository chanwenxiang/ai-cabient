package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import com.aicabinet.trade.config.JsonStringTypeHandler;
import com.aicabinet.common.enums.SessionState;
import java.time.Instant;

@TableName(value = "shopping_session", autoResultMap = true)
public class ShoppingSession {

    @TableId(type = IdType.INPUT)
    private String sessionId;

    private Long userId;

    private String deviceId;

    private SessionState state;

    private Instant openTime;
    private Instant closeTime;

    private String orderId;

    private String failReason;

    private String recognitionTaskId;

    private String videoUri;

    private String uploadStatus = "NONE";

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String videoClips;

    private String cameraFusionMode = "SINGLE";

    private String idempotencyKey;

    private Long replenishmentTaskId;

    @TableField(typeHandler = JsonStringTypeHandler.class)
    private String gravityDeltas;

    /** 扫码入口渠道 WECHAT / ALIPAY */
    private String entryChannel;

    private Instant createdAt;

    private Instant updatedAt;

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
    public String getEntryChannel() { return entryChannel; }
    public void setEntryChannel(String entryChannel) { this.entryChannel = entryChannel; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
