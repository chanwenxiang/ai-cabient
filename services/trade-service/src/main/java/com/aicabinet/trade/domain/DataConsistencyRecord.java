package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;

@TableName("data_consistency_record")
public class DataConsistencyRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    
    private String checkType;
    
    private String tableName;
    
    private String checkKey;
    
    private String expectedValue;
    
    private String actualValue;
    
    private String status;
    
    private String errorMessage;
    
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
