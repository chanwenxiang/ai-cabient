package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("compensation_task")
@Getter
@Setter
public class CompensationTask {
    @TableId(type = IdType.AUTO)
    private Long taskId;

    
    private String txId;
    
    private String taskType;
    
    private Integer priority = 0;
    
    private Instant scheduledAt;
    
    private Instant executedAt;
    
    private String status = "PENDING";
    
    private String result;
    
    private int retryCount = 0;

    private Instant createdAt = Instant.now();
    
    
    
    
    
    
    
    
    
}
