package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "red_packet_claim")
public class RedPacketClaim {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long packetId;
    
    @Column(nullable = false)
    private Long userId;
    
    @Column(precision = 10, scale = 2, nullable = false)
    private java.math.BigDecimal claimedAmount;
    
    @Column(nullable = false)
    private Instant claimedAt = Instant.now();
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getPacketId() { return packetId; }
    public void setPacketId(Long packetId) { this.packetId = packetId; }
    
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    
    public java.math.BigDecimal getClaimedAmount() { return claimedAmount; }
    public void setClaimedAmount(java.math.BigDecimal claimedAmount) { this.claimedAmount = claimedAmount; }
    
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
}
