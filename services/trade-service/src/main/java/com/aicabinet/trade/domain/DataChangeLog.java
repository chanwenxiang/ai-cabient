package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "data_change_log")
public class DataChangeLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(length = 64, nullable = false)
    private String tableName;
    
    @Column(length = 64, nullable = false)
    private String recordId;
    
    @Column(length = 16, nullable = false)
    private String operation;
    
    @Column(columnDefinition = "jsonb")
    private String oldValue;
    
    @Column(columnDefinition = "jsonb")
    private String newValue;
    
    @Column(length = 64)
    private String changedBy;
    
    @Column(nullable = false)
    private Instant changedAt = Instant.now();
    
    private Boolean verified = false;
    
    private Instant verifiedAt;
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    
    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }
    
    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    
    public String getOldValue() { return oldValue; }
    public void setOldValue(String oldValue) { this.oldValue = oldValue; }
    
    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }
    
    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
    
    public Instant getChangedAt() { return changedAt; }
    public void setChangedAt(Instant changedAt) { this.changedAt = changedAt; }
    
    public Boolean getVerified() { return verified; }
    public void setVerified(Boolean verified) { this.verified = verified; }
    
    public Instant getVerifiedAt() { return verifiedAt; }
    public void setVerifiedAt(Instant verifiedAt) { this.verifiedAt = verifiedAt; }
}
