package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("approval_instance")
@Getter
@Setter
public class ApprovalInstance {
    @TableId(type = IdType.AUTO)
    private Long instanceId;
    private Long defId;
    private String bizType;
    private String bizId;
    private String title;
    private String status = "PENDING";
    private Long submitterId;
    private Integer currentNodeSeq = 1;
    private String remark;
    private Instant createdAt = Instant.now();
    private Instant finishedAt;
}
