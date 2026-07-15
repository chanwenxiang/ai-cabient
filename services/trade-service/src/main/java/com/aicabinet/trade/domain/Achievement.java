package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "achievement")
public class Achievement {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long achievementId;
    
    @Column(length = 64, nullable = false)
    private String achievementCode;
    
    @Column(length = 100, nullable = false)
    private String achievementName;
    
    @Column(length = 200)
    private String description;
    
    @Column(length = 32, nullable = false)
    private String category;
    
    @Column(length = 64)
    private String iconUrl;
    
    @Column(nullable = false)
    private Integer requiredProgress;
    
    @Column(nullable = false)
    private Integer rewardPoints;
    
    @Column(length = 16, nullable = false)
    private String status;
    
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    
    public Long getAchievementId() { return achievementId; }
    public void setAchievementId(Long achievementId) { this.achievementId = achievementId; }
    
    public String getAchievementCode() { return achievementCode; }
    public void setAchievementCode(String achievementCode) { this.achievementCode = achievementCode; }
    
    public String getAchievementName() { return achievementName; }
    public void setAchievementName(String achievementName) { this.achievementName = achievementName; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    
    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
    
    public Integer getRequiredProgress() { return requiredProgress; }
    public void setRequiredProgress(Integer requiredProgress) { this.requiredProgress = requiredProgress; }
    
    public Integer getRewardPoints() { return rewardPoints; }
    public void setRewardPoints(Integer rewardPoints) { this.rewardPoints = rewardPoints; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
