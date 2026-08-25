package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Data;

@Data
@TableName("replenishment_task")
public class ReplenishmentTask {

    @TableId(type = IdType.AUTO)
    private Long taskId;

    private Long routeId;

    private String deviceId;

    private Long assigneeUserId;

    private String status = "PENDING";

    private String notes;

    private Instant completedAt;

    private Long outboundId;

    private Long requestId;

    private Instant checkInAt;

    private Double checkInLat;

    private Double checkInLng;

    private Instant createdAt;

}
