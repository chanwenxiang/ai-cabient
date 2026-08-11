package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("transaction_step")
@Getter
@Setter
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
    
    
    
    
    
    
    
    
    
    
    
}
