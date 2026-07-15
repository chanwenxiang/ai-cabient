package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ad_campaign")
public class AdCampaign {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long campaignId;
    
    @Column(length = 100, nullable = false)
    private String campaignName;
    
    @Column(length = 32, nullable = false)
    private String campaignType;
    
    @Column(length = 64)
    private String advertiserId;
    
    @Column(length = 200)
    private String imageUrl;
    
    @Column(length = 256)
    private String targetUrl;
    
    @Column(nullable = false)
    private Long slotId;
    
    @Column(precision = 12, scale = 2)
    private java.math.BigDecimal budget;
    
    @Column(precision = 12, scale = 2)
    private java.math.BigDecimal spent;
    
    @Column(nullable = false)
    private Instant startTime;
    
    private Instant endTime;
    
    @Column(length = 16, nullable = false)
    private String status;
    
    @Column(nullable = false)
    private Integer priority = 0;
    
    @Column(nullable = false)
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
