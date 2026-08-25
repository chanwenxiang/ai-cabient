package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("idempotency_key")
@Getter
@Setter
public class IdempotencyKey {
    
    @TableId(type = IdType.INPUT)
    private String idempotencyKey;

    
    private String businessType;
    
    private String businessId;
    
    private String requestHash;
    
    private com.fasterxml.jackson.databind.JsonNode responseData;
    
    private Instant expireAt;
    
    private Instant createdAt = Instant.now();
    
    
    
    
    
    
    
}