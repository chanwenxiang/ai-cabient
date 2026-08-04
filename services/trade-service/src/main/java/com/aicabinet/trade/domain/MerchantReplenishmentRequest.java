package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("merchant_replenishment_request")
public class MerchantReplenishmentRequest {

    @TableId(type = IdType.AUTO)
    private Long requestId;

    private String merchantId;

    private String deviceId;

    private String status = "SUBMITTED";

    private String notes;

    private Long createdBy;

    private Instant submittedAt;

    private Instant reviewedAt;

    private Long reviewerId;

    private Long replenishmentTaskId;

    private Long outboundId;

    private String rejectReason;

    private Instant createdAt;

public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }
    public String getMerchantId() { return merchantId; }
    public void setMerchantId(String merchantId) { this.merchantId = merchantId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public Long getReplenishmentTaskId() { return replenishmentTaskId; }
    public void setReplenishmentTaskId(Long replenishmentTaskId) { this.replenishmentTaskId = replenishmentTaskId; }
    public Long getOutboundId() { return outboundId; }
    public void setOutboundId(Long outboundId) { this.outboundId = outboundId; }
    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }
    public Instant getCreatedAt() { return createdAt; }
}
