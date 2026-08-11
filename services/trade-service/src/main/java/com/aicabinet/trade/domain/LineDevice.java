package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("line_device")
@Getter
@Setter
public class LineDevice {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long managerId;
    private String deviceId;
    private String status;
    private Instant assignedAt;
    private Instant unassignedAt;

}
