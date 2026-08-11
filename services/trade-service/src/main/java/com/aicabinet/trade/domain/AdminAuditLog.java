package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("admin_audit_log")
@Getter
@Setter
public class AdminAuditLog {

    @TableId(type = IdType.AUTO)
    private Long logId;

    private Long operatorId;

    private String action;

    private String targetType;

    private String targetId;

    private String detail;

    private Instant createdAt;

}
