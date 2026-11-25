package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("purchase_receive_allocation")
@Getter
@Setter
public class PurchaseReceiveAllocation {
    @TableId(type = IdType.AUTO)
    private Long allocationId;
    private Long purchaseOrderId;
    private Long purchaseLineId;
    private String skuId;
    private String warehouseId;
    private String batchNo;
    private int receivedQty;
    private int returnedQty;
    private Instant createdAt;
}
