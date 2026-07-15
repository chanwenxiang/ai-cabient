package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "member_points_log")
public class MemberPointsLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long memberId;
    
    @Column(nullable = false)
    private Integer points;
    
    @Column(length = 16, nullable = false)
    private String pointsType;
    
    @Column(length = 64)
    private String sourceType;
    
    @Column(length = 64)
    private String sourceId;
    
    @Column(length = 200)
    private String description;
    
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    
    private Instant expireAt;
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getMemberId() { return memberId; }
    public void setMemberId(Long memberId) { this.memberId = memberId; }
    
    public Integer getPoints() { return points; }
    public void setPoints(Integer points) { this.points = points; }
    
    public String getPointsType() { return pointsType; }
    public void setPointsType(String pointsType) { this.pointsType = pointsType; }
    
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    
    public String getSourceId() { return sourceId; }
    public void setSourceId(String sourceId) { this.sourceId = sourceId; }
    
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
}
