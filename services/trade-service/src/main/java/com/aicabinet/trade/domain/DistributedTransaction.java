package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("distributed_transaction")
@Getter
@Setter
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
    
    
    
    
    
    
    
    
    
    
    
}
