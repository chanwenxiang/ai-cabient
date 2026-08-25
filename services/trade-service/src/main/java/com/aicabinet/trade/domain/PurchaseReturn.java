package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("purchase_return")
@Getter
@Setter
public class PurchaseReturn {
    @TableId(type = IdType.AUTO)
    private Long returnId;
    private Long purchaseOrderId;
    private String warehouseId;
    private String supplierId;
    private String status = "COMPLETED";
    private String notes;
    private Long operatorId;
    private Instant createdAt;

}
