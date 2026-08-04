package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("red_packet")
public class RedPacket {
    @TableId(type = IdType.AUTO)
    private Long packetId;

    
    private Long senderId;
    
    private String packetCode;
    
    private java.math.BigDecimal totalAmount;
    
    private Integer totalCount;
    
    private Integer claimedCount = 0;
    
    private java.math.BigDecimal minAmount;
    
    private java.math.BigDecimal maxAmount;
    
    private String message;
    
    private String status;
    
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
