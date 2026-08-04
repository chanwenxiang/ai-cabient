package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("red_packet_claim")
public class RedPacketClaim {
    @TableId(type = IdType.AUTO)
    private Long id;

    
    private Long packetId;
    
    private Long userId;
    
    private java.math.BigDecimal claimedAmount;
    
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
