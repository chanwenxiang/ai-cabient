package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("distributed_transaction")
public class DistributedTransaction {
    @TableId(type = IdType.INPUT)
    private String txId;

    
    private String txType;
    
    private String status;
    
    private Integer retryCount = 0;
    
    private Integer maxRetry = 5;
    
    private String payload;
    
    private String compensationSql;
    
    private String errorMessage;
    
    private Instant createdAt = Instant.now();
    
    private Instant updatedAt = Instant.now();
    
    private Instant completedAt;
    
    public String getTxId() { return txId; }
    public void setTxId(String txId) { this.txId = txId; }
    
    public String getTxType() { return txType; }
    public void setTxType(String txType) { this.txType = txType; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    
    public Integer getMaxRetry() { return maxRetry; }
    public void setMaxRetry(Integer maxRetry) { this.maxRetry = maxRetry; }
    
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    
    public String getCompensationSql() { return compensationSql; }
    public void setCompensationSql(String compensationSql) { this.compensationSql = compensationSql; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
