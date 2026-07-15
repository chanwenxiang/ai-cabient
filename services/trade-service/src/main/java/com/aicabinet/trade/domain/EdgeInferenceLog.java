package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "edge_inference_log")
public class EdgeInferenceLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(length = 32, nullable = false)
    private String deviceId;
    
    @Column(length = 64)
    private String sessionId;
    
    @Column(length = 32, nullable = false)
    private String modelType;
    
    @Column(length = 64)
    private String modelName;
    
    @Column(nullable = false)
    private Integer inferenceTimeMs;
    
    @Column(length = 16, nullable = false)
    private String status;
    
    @Column(nullable = false)
    private Instant inferenceAt = Instant.now();
    
    @Column(length = 64)
    private String resultSkuId;
    
    @Column(precision = 5, scale = 2)
    private java.math.BigDecimal confidence;
    
    @Column(columnDefinition = "text")
    private String errorMessage;
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    
    public String getModelType() { return modelType; }
    public void setModelType(String modelType) { this.modelType = modelType; }
    
    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }
    
    public Integer getInferenceTimeMs() { return inferenceTimeMs; }
    public void setInferenceTimeMs(Integer inferenceTimeMs) { this.inferenceTimeMs = inferenceTimeMs; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getInferenceAt() { return inferenceAt; }
    public void setInferenceAt(Instant inferenceAt) { this.inferenceAt = inferenceAt; }
    
    public String getResultSkuId() { return resultSkuId; }
    public void setResultSkuId(String resultSkuId) { this.resultSkuId = resultSkuId; }
    
    public java.math.BigDecimal getConfidence() { return confidence; }
    public void setConfidence(java.math.BigDecimal confidence) { this.confidence = confidence; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
