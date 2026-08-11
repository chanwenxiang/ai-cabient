package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("warehouse_in_transit")
@Getter
@Setter
public class WarehouseInTransit {

    @TableId(type = IdType.AUTO)
    private Long transitId;

    private Long outboundId;

    private String deviceId;

    private String skuId;

    private String batchNo;

    private int quantity;

    private String status = "IN_TRANSIT";

    private Instant createdAt;

    private Instant receivedAt;

}
