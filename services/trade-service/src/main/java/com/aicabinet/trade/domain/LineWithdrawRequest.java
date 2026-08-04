package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;

@TableName("line_withdraw_request")
public class LineWithdrawRequest {

    @TableId(type = IdType.AUTO)
    private Long requestId;
    private String requestNo;
    private Long managerId;
    private Long amountCents;
    private String status;
    private String payChannel;
    private Long reviewerId;
    private String reviewRemark;
    private Instant reviewedAt;
    private String payoutRef;
    private String payoutMessage;
    private Instant paidAt;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }
    public String getRequestNo() { return requestNo; }
    public void setRequestNo(String requestNo) { this.requestNo = requestNo; }
    public Long getManagerId() { return managerId; }
    public void setManagerId(Long managerId) { this.managerId = managerId; }
    public Long getAmountCents() { return amountCents; }
    public void setAmountCents(Long amountCents) { this.amountCents = amountCents; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPayChannel() { return payChannel; }
    public void setPayChannel(String payChannel) { this.payChannel = payChannel; }
    public Long getReviewerId() { return reviewerId; }
    public void setReviewerId(Long reviewerId) { this.reviewerId = reviewerId; }
    public String getReviewRemark() { return reviewRemark; }
    public void setReviewRemark(String reviewRemark) { this.reviewRemark = reviewRemark; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public String getPayoutRef() { return payoutRef; }
    public void setPayoutRef(String payoutRef) { this.payoutRef = payoutRef; }
    public String getPayoutMessage() { return payoutMessage; }
    public void setPayoutMessage(String payoutMessage) { this.payoutMessage = payoutMessage; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
