package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("data_change_log")
@Getter
@Setter
public class DataChangeLog {
    @TableId(type = IdType.AUTO)
    private Long id;

    
    private String tableName;
    
    private String recordId;
    
    private String operation;
    
    private String oldValue;
    
    private String newValue;
    
    private String changedBy;
    
    private Instant changedAt = Instant.now();
    
    private Boolean verified = false;
    
    private Instant verifiedAt;
    
    
    
    
    
    
    
    
    
    
}
