package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("group_buy")
public class GroupBuy {
    @TableId(type = IdType.AUTO)
    private Long groupBuyId;

    
    private String title;
    
    private Long skuId;
    
    private java.math.BigDecimal originalPrice;
    
    private java.math.BigDecimal groupPrice;
    
    private Integer minParticipants;
    
    private Integer maxParticipants;
    
    private Integer currentParticipants = 0;
    
    private Instant startTime;
    
    private Instant endTime;
    
    private String status;
    
    private Instant createdAt = Instant.now();
    
    private Instant updatedAt;
    
    public Long getGroupBuyId() { return groupBuyId; }
    public void setGroupBuyId(Long groupBuyId) { this.groupBuyId = groupBuyId; }
    
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    
    public Long getSkuId() { return skuId; }
    public void setSkuId(Long skuId) { this.skuId = skuId; }
    
    public java.math.BigDecimal getOriginalPrice() { return originalPrice; }
    public void setOriginalPrice(java.math.BigDecimal originalPrice) { this.originalPrice = originalPrice; }
    
    public java.math.BigDecimal getGroupPrice() { return groupPrice; }
    public void setGroupPrice(java.math.BigDecimal groupPrice) { this.groupPrice = groupPrice; }
    
    public Integer getMinParticipants() { return minParticipants; }
    public void setMinParticipants(Integer minParticipants) { this.minParticipants = minParticipants; }
    
    public Integer getMaxParticipants() { return maxParticipants; }
    public void setMaxParticipants(Integer maxParticipants) { this.maxParticipants = maxParticipants; }
    
    public Integer getCurrentParticipants() { return currentParticipants; }
    public void setCurrentParticipants(Integer currentParticipants) { this.currentParticipants = currentParticipants; }
    
    public Instant getStartTime() { return startTime; }
    public void setStartTime(Instant startTime) { this.startTime = startTime; }
    
    public Instant getEndTime() { return endTime; }
    public void setEndTime(Instant endTime) { this.endTime = endTime; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
