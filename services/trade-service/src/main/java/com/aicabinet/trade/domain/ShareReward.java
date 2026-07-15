package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "share_reward")
public class ShareReward {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long rewardId;
    
    @Column(nullable = false)
    private Long sharerId;
    
    @Column
    private Long inviteeId;
    
    @Column(length = 32)
    private String orderId;
    
    @Column(length = 16, nullable = false)
    private String rewardType;
    
    @Column(precision = 10, scale = 2)
    private java.math.BigDecimal rewardAmount;
    
    @Column
    private Integer rewardPoints;
    
    @Column(length = 16, nullable = false)
    private String status;
    
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    
    private Instant claimedAt;
    
    public Long getRewardId() { return rewardId; }
    public void setRewardId(Long rewardId) { this.rewardId = rewardId; }
    
    public Long getSharerId() { return sharerId; }
    public void setSharerId(Long sharerId) { this.sharerId = sharerId; }
    
    public Long getInviteeId() { return inviteeId; }
    public void setInviteeId(Long inviteeId) { this.inviteeId = inviteeId; }
    
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    
    public String getRewardType() { return rewardType; }
    public void setRewardType(String rewardType) { this.rewardType = rewardType; }
    
    public java.math.BigDecimal getRewardAmount() { return rewardAmount; }
    public void setRewardAmount(java.math.BigDecimal rewardAmount) { this.rewardAmount = rewardAmount; }
    
    public Integer getRewardPoints() { return rewardPoints; }
    public void setRewardPoints(Integer rewardPoints) { this.rewardPoints = rewardPoints; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
}
