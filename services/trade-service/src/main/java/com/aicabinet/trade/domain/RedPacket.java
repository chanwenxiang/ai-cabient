package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "red_packet")
public class RedPacket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long packetId;
    
    @Column(nullable = false)
    private Long senderId;
    
    @Column(length = 32, nullable = false)
    private String packetCode;
    
    @Column(precision = 10, scale = 2, nullable = false)
    private java.math.BigDecimal totalAmount;
    
    @Column(nullable = false)
    private Integer totalCount;
    
    @Column(nullable = false)
    private Integer claimedCount = 0;
    
    @Column(precision = 10, scale = 2, nullable = false)
    private java.math.BigDecimal minAmount;
    
    @Column(precision = 10, scale = 2, nullable = false)
    private java.math.BigDecimal maxAmount;
    
    @Column(length = 200)
    private String message;
    
    @Column(length = 16, nullable = false)
    private String status;
    
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    
    private Instant expiredAt;
    
    public Long getPacketId() { return packetId; }
    public void setPacketId(Long packetId) { this.packetId = packetId; }
    
    public Long getSenderId() { return senderId; }
    public void setSenderId(Long senderId) { this.senderId = senderId; }
    
    public String getPacketCode() { return packetCode; }
    public void setPacketCode(String packetCode) { this.packetCode = packetCode; }
    
    public java.math.BigDecimal getTotalAmount() { return totalAmount; }
    public void setTotalAmount(java.math.BigDecimal totalAmount) { this.totalAmount = totalAmount; }
    
    public Integer getTotalCount() { return totalCount; }
    public void setTotalCount(Integer totalCount) { this.totalCount = totalCount; }
    
    public Integer getClaimedCount() { return claimedCount; }
    public void setClaimedCount(Integer claimedCount) { this.claimedCount = claimedCount; }
    
    public java.math.BigDecimal getMinAmount() { return minAmount; }
    public void setMinAmount(java.math.BigDecimal minAmount) { this.minAmount = minAmount; }
    
    public java.math.BigDecimal getMaxAmount() { return maxAmount; }
    public void setMaxAmount(java.math.BigDecimal maxAmount) { this.maxAmount = maxAmount; }
    
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getExpiredAt() { return expiredAt; }
    public void setExpiredAt(Instant expiredAt) { this.expiredAt = expiredAt; }
}
