package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("repair_ticket")
@Getter
@Setter
public class RepairTicket {

    @TableId(type = IdType.AUTO)
    private Long ticketId;
    private String deviceId;
    private String title;
    private String faultType;
    private String status;
    private String assignee;
    private String priority;
    private String remark;
    private Long createdBy;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant closedAt;

}
