package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("warehouse_outbound")
@Getter
@Setter
public class WarehouseOutbound {

    @TableId(type = IdType.AUTO)
    private Long outboundId;

    private String warehouseId;

    private Long routeId;

    private String status = "DRAFT";

    private Long assigneeUserId;

    private String notes;

    private Instant createdAt;

    private Instant shippedAt;
    private String handoverStatus = "PENDING";
    private Long handoverOperatorId;
    private Instant handedOverAt;

}
