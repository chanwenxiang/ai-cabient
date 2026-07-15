package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "compensation_task")
public class CompensationTask {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long taskId;
    
    @Column(length = 64, nullable = false)
    private String txId;
    
    @Column(length = 32, nullable = false)
    private String taskType;
    
    @Column(nullable = false)
    private Integer priority = 0;
    
    @Column(nullable = false)
    private Instant scheduledAt;
    
    private Instant executedAt;
    
    @Column(length = 16, nullable = false)
    private String status = "PENDING";
    
    @Column(columnDefinition = "text")
    private String result;
    
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
    
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    
    public String getTxId() { return txId; }
    public void setTxId(String txId) { this.txId = txId; }
    
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    
    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }
    
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }
    
    public Instant getExecutedAt() { return executedAt; }
    public void setExecutedAt(Instant executedAt) { this.executedAt = executedAt; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
