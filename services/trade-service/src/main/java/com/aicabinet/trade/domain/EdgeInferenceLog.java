package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("edge_inference_log")
public class EdgeInferenceLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    
    private String deviceId;
    
    private String sessionId;
    
    private String modelType;
    
    private String modelName;
    
    private Integer inferenceTimeMs;
    
    private String status;
    
    private Instant inferenceAt = Instant.now();
    
    private String resultSkuId;
    
    private java.math.BigDecimal confidence;
    
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
