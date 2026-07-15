package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "ad_slot")
public class AdSlot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long slotId;
    
    @Column(length = 100, nullable = false)
    private String slotName;
    
    @Column(length = 32, nullable = false)
    private String slotCode;
    
    @Column(length = 32, nullable = false)
    private String slotType;
    
    @Column(length = 32)
    private String deviceId;
    
    @Column(length = 64)
    private String position;
    
    @Column(nullable = false)
    private Integer width;
    
    @Column(nullable = false)
    private Integer height;
    
    @Column(precision = 10, scale = 2)
    private java.math.BigDecimal defaultPrice;
    
    @Column(length = 16, nullable = false)
    private String status;
    
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    
    private Instant updatedAt;
    
    public Long getSlotId() { return slotId; }
    public void setSlotId(Long slotId) { this.slotId = slotId; }
    
    public String getSlotName() { return slotName; }
    public void setSlotName(String slotName) { this.slotName = slotName; }
    
    public String getSlotCode() { return slotCode; }
    public void setSlotCode(String slotCode) { this.slotCode = slotCode; }
    
    public String getSlotType() { return slotType; }
    public void setSlotType(String slotType) { this.slotType = slotType; }
    
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    
    public Integer getWidth() { return width; }
    public void setWidth(Integer width) { this.width = width; }
    
    public Integer getHeight() { return height; }
    public void setHeight(Integer height) { this.height = height; }
    
    public java.math.BigDecimal getDefaultPrice() { return defaultPrice; }
    public void setDefaultPrice(java.math.BigDecimal defaultPrice) { this.defaultPrice = defaultPrice; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
