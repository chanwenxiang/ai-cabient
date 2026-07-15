package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "idempotency_key")
public class IdempotencyKey {
    
    @Id
    @Column(length = 128)
    private String idempotencyKey;
    
    @Column(length = 32, nullable = false)
    private String businessType;
    
    @Column(length = 64, nullable = false)
    private String businessId;
    
    @Column(length = 64)
    private String requestHash;
    
    @Column(columnDefinition = "JSONB")
    private com.fasterxml.jackson.databind.JsonNode responseData;
    
    @Column(nullable = false)
    private Instant expireAt;
    
    @Column(nullable = false)
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