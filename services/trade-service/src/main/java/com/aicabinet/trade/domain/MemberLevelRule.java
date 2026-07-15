package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("member_level_rule")
public class MemberLevelRule {
    @TableId(type = IdType.AUTO)
    private Long id;

    
    private String levelCode;
    
    private String levelName;
    
    private java.math.BigDecimal minSpent;
    
    private java.math.BigDecimal maxSpent;
    
    private Integer minPoints;
    
    private Integer maxPoints;
    
    private java.math.BigDecimal pointsRate;
    
    private Integer sortorder;
    
    private String status;
    
    private Instant createdAt = Instant.now();
    
    private Instant updatedAt;
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getLevelCode() { return levelCode; }
    public void setLevelCode(String levelCode) { this.levelCode = levelCode; }
    
    public String getLevelName() { return levelName; }
    public void setLevelName(String levelName) { this.levelName = levelName; }
    
    public java.math.BigDecimal getMinSpent() { return minSpent; }
    public void setMinSpent(java.math.BigDecimal minSpent) { this.minSpent = minSpent; }
    
    public java.math.BigDecimal getMaxSpent() { return maxSpent; }
    public void setMaxSpent(java.math.BigDecimal maxSpent) { this.maxSpent = maxSpent; }
    
    public Integer getMinPoints() { return minPoints; }
    public void setMinPoints(Integer minPoints) { this.minPoints = minPoints; }
    
    public Integer getMaxPoints() { return maxPoints; }
    public void setMaxPoints(Integer maxPoints) { this.maxPoints = maxPoints; }
    
    public java.math.BigDecimal getPointsRate() { return pointsRate; }
    public void setPointsRate(java.math.BigDecimal pointsRate) { this.pointsRate = pointsRate; }
    
    public Integer getSortorder() { return sortorder; }
    public void setSortorder(Integer sortorder) { this.sortorder = sortorder; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
