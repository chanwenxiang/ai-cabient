package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("ad_campaign")
public class AdCampaign {
    @TableId(type = IdType.AUTO)
    private Long campaignId;

    
    private String campaignName;
    
    private String campaignType;
    
    private String advertiserId;
    
    private String imageUrl;
    
    private String targetUrl;
    
    private Long slotId;
    
    private java.math.BigDecimal budget;
    
    private java.math.BigDecimal spent;
    
    private Instant startTime;
    
    private Instant endTime;
    
    private String status;
    
    private Integer priority = 0;
    
    private Instant createdAt = Instant.now();
    
    private Instant updatedAt;
    
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
    
    public String getCampaignName() { return campaignName; }
    public void setCampaignName(String campaignName) { this.campaignName = campaignName; }
    
    public String getCampaignType() { return campaignType; }
    public void setCampaignType(String campaignType) { this.campaignType = campaignType; }
    
    public String getAdvertiserId() { return advertiserId; }
    public void setAdvertiserId(String advertiserId) { this.advertiserId = advertiserId; }
    
    public String getImageUrl() { return imageUrl; }
    public void setImageUrl(String imageUrl) { this.imageUrl = imageUrl; }
    
    public String getTargetUrl() { return targetUrl; }
    public void setTargetUrl(String targetUrl) { this.targetUrl = targetUrl; }
    
    public Long getSlotId() { return slotId; }
    public void setSlotId(Long slotId) { this.slotId = slotId; }
    
    public java.math.BigDecimal getBudget() { return budget; }
    public void setBudget(java.math.BigDecimal budget) { this.budget = budget; }
    
    public java.math.BigDecimal getSpent() { return spent; }
    public void setSpent(java.math.BigDecimal spent) { this.spent = spent; }
    
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
