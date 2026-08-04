package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("group_buy_participant")
public class GroupBuyParticipant {
    @TableId(type = IdType.AUTO)
    private Long id;

    
    private Long groupBuyId;
    
    private Long userId;
    
    private String orderId;
    
    private String status;
    
    private Instant joinedAt = Instant.now();
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public Long getGroupBuyId() { return groupBuyId; }
    public void setGroupBuyId(Long groupBuyId) { this.groupBuyId = groupBuyId; }
    
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    
    public String getOrderId() { return orderId; }
    public void setOrderId(String orderId) { this.orderId = orderId; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getJoinedAt() { return joinedAt; }
    public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
}
