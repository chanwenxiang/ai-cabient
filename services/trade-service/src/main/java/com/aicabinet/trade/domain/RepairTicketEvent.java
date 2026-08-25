package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("repair_ticket_event")
@Getter
@Setter
public class RepairTicketEvent {

    @TableId(type = IdType.AUTO)
    private Long eventId;
    private Long ticketId;
    private String fromStatus;
    private String toStatus;
    private String action;
    private Long operatorId;
    private String remark;
    private Instant createdAt;

}
