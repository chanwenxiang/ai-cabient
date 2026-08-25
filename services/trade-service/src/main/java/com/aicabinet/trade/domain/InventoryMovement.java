package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("inventory_movement")
@Getter
@Setter
public class InventoryMovement {

    @TableId(type = IdType.AUTO)
    private Long movementId;

    private String deviceId;

    private String skuId;

    private String batchNo;

    private String movementType;

    private int deltaQty;

    private String refType;

    private String refId;

    private Long operatorId;

    private Instant createdAt;

}
