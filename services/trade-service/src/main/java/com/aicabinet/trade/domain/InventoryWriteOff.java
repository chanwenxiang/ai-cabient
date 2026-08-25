package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("inventory_write_off")
@Getter
@Setter
public class InventoryWriteOff {

    @TableId(type = IdType.AUTO)
    private Long writeOffId;

    private String deviceId;

    private String skuId;

    private String batchNo;

    private int quantity;

    private String reason;

    private Integer costCents;

    private Long operatorId;

    private Instant createdAt;

}
