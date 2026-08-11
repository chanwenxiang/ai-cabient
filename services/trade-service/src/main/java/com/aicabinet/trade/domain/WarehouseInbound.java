package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("warehouse_inbound")
@Getter
@Setter
public class WarehouseInbound {

    @TableId(type = IdType.AUTO)
    private Long inboundId;

    private String warehouseId;

    private String refNo;

    private String status = "COMPLETED";

    private Long purchaseOrderId;

    private Long operatorId;

    private String notes;

    private Instant createdAt;

}
