package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "member_level_rule")
public class MemberLevelRule {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(length = 16, nullable = false)
    private String levelCode;
    
    @Column(length = 32, nullable = false)
    private String levelName;
    
    @Column(precision = 12, scale = 2)
    private java.math.BigDecimal minSpent;
    
    @Column(precision = 12, scale = 2)
    private java.math.BigDecimal maxSpent;
    
    @Column(nullable = false)
    private Integer minPoints;
    
    @Column
    private Integer maxPoints;
    
    @Column(precision = 5, scale = 2)
    private java.math.BigDecimal pointsRate;
    
    @Column(nullable = false)
    private Integer sortorder;
    
    @Column(length = 16, nullable = false)
    private String status;
    
    @Column(nullable = false)
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
