package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("approval_task")
@Getter
@Setter
public class ApprovalTask {
    @TableId(type = IdType.AUTO)
    private Long taskId;
    private Long instanceId;
    private Integer nodeSeq;
    private String nodeName;
    private Long assigneeUserId;
    private String status = "PENDING";
    private String remark;
    private Instant actedAt;
    private Instant readAt;
    private Instant createdAt = Instant.now();
}
