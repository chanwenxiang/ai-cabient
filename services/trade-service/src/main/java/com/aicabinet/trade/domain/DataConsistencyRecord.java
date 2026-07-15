package com.aicabinet.trade.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "data_consistency_record")
public class DataConsistencyRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(length = 64, nullable = false)
    private String checkType;
    
    @Column(length = 64, nullable = false)
    private String tableName;
    
    @Column(length = 64)
    private String checkKey;
    
    @Column(columnDefinition = "text")
    private String expectedValue;
    
    @Column(columnDefinition = "text")
    private String actualValue;
    
    @Column(length = 16, nullable = false)
    private String status;
    
    @Column(columnDefinition = "text")
    private String errorMessage;
    
    @Column(nullable = false)
    private Instant checkedAt = Instant.now();
    
    private Instant fixedAt;
    
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getCheckType() { return checkType; }
    public void setCheckType(String checkType) { this.checkType = checkType; }
    
    public String getTableName() { return tableName; }
    public void setTableName(String tableName) { this.tableName = tableName; }
    
    public String getCheckKey() { return checkKey; }
    public void setCheckKey(String checkKey) { this.checkKey = checkKey; }
    
    public String getExpectedValue() { return expectedValue; }
    public void setExpectedValue(String expectedValue) { this.expectedValue = expectedValue; }
    
    public String getActualValue() { return actualValue; }
    public void setActualValue(String actualValue) { this.actualValue = actualValue; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public Instant getCheckedAt() { return checkedAt; }
    public void setCheckedAt(Instant checkedAt) { this.checkedAt = checkedAt; }
    
    public Instant getFixedAt() { return fixedAt; }
    public void setFixedAt(Instant fixedAt) { this.fixedAt = fixedAt; }
}
