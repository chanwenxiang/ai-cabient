package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("idempotency_key")
public class IdempotencyKey {
    
    @TableId(type = IdType.INPUT)
    private String idempotencyKey;

    
    private String businessType;
    
    private String businessId;
    
    private String requestHash;
    
    private com.fasterxml.jackson.databind.JsonNode responseData;
    
    private Instant expireAt;
    
    private Instant createdAt = Instant.now();
    
    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
    
    public String getBusinessType() { return businessType; }
    public void setBusinessType(String businessType) { this.businessType = businessType; }
    
    public String getBusinessId() { return businessId; }
    public void setBusinessId(String businessId) { this.businessId = businessId; }
    
    public String getRequestHash() { return requestHash; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    
    public com.fasterxml.jackson.databind.JsonNode getResponseData() { return responseData; }
    public void setResponseData(com.fasterxml.jackson.databind.JsonNode responseData) { this.responseData = responseData; }
    
    public Instant getExpireAt() { return expireAt; }
    public void setExpireAt(Instant expireAt) { this.expireAt = expireAt; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}