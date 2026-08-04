package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("transaction_step")
public class TransactionStep {
    @TableId(type = IdType.AUTO)
    private Long stepId;

    
    private String txId;
    
    private Integer stepOrder;
    
    private String stepName;
    
    private String stepType;
    
    private String status;
    
    private String requestData;
    
    private String responseData;
    
    private String errorMessage;
    
    private Instant executedAt;
    
    private Instant createdAt = Instant.now();
    
    public Long getStepId() { return stepId; }
    public void setStepId(Long stepId) { this.stepId = stepId; }
    
    public String getTxId() { return txId; }
    public void setTxId(String txId) { this.txId = txId; }
    
    public Integer getStepOrder() { return stepOrder; }
    public void setStepOrder(Integer stepOrder) { this.stepOrder = stepOrder; }
    
    public String getStepName() { return stepName; }
    public void setStepName(String stepName) { this.stepName = stepName; }
    
    public String getStepType() { return stepType; }
    public void setStepType(String stepType) { this.stepType = stepType; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getRequestData() { return requestData; }
    public void setRequestData(String requestData) { this.requestData = requestData; }
    
    public String getResponseData() { return responseData; }
    public void setResponseData(String responseData) { this.responseData = responseData; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
