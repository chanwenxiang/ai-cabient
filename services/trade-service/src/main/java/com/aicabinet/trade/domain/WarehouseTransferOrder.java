package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("warehouse_transfer_order")
@Getter
@Setter
public class WarehouseTransferOrder {
    @TableId(type = IdType.AUTO)
    private Long transferId;
    private String transferNo;
    private String fromWarehouseId;
    private String toWarehouseId;
    private String status = "DRAFT";
    private Long operatorId;
    private String notes;
    private Instant shippedAt;
    private Instant receivedAt;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();
}
