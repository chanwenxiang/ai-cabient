package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("share_reward")
public class ShareReward {
    @TableId(type = IdType.AUTO)
    private Long rewardId;

    
    private Long sharerId;
    
    private Long inviteeId;
    
    private String orderId;
    
    private String rewardType;
    
    private java.math.BigDecimal rewardAmount;
    
    private Integer rewardPoints;
    
    private String status;
    
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
