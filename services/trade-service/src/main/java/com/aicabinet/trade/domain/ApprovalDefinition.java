package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("approval_definition")
@Getter
@Setter
public class ApprovalDefinition {
    @TableId(type = IdType.AUTO)
    private Long defId;
    private String bizType;
    private String defName;
    private Boolean enabled = true;
    private String remark;
    private Instant createdAt = Instant.now();
}
