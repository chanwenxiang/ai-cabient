package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ad_impression")
public class AdImpression {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long campaignId;
    
    @Column(nullable = false)
    private Long slotId;
    
    @Column(length = 32)
    private String deviceId;
    
    @Column(length = 64)
    private String userId;
    
    @Column(length = 16, nullable = false)
    private String eventType;
    
    @Column(precision = 10, scale = 4)
    private java.math.BigDecimal cost;
    
    @Column(nullable = false)
    private Instant eventTime = Instant.now();
    
    @Column(length = 64)
    private String sessionId;
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
    
    public Long getSlotId() { return slotId; }
    public void setSlotId(Long slotId) { this.slotId = slotId; }
    
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    
    public java.math.BigDecimal getCost() { return cost; }
    public void setCost(java.math.BigDecimal cost) { this.cost = cost; }
    
    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }
    
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
}
