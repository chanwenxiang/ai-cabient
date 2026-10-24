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

    /** 客户端幂等键（V401，自主补货 clientRequestNo 契约）：同键重放返回原任务；NULL=旧客户端走 90s 短窗。 */
    private String clientRequestNo;

    private Instant completedAt;

    private Long outboundId;

    private Instant checkInAt;

    private Double checkInLat;

    private Double checkInLng;

    private Instant createdAt;

}
