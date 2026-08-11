package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("data_consistency_record")
@Getter
@Setter
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
    
    
    
    
    
    
    
    
    
    
}
