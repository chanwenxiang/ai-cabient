package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("purchase_order")
@Getter
@Setter
public class PurchaseOrder {
    @TableId(type = IdType.AUTO)
    private Long purchaseOrderId;

    private String supplierId;
    private String warehouseId;
    private String status = "CREATED";
    private String refNo;
    private Long operatorId;
    private String notes;
    private Instant createdAt;
    private Instant receivedAt;

}
