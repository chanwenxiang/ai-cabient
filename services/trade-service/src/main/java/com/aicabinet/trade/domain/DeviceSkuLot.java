package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName("device_sku_lot")
@Getter
@Setter
public class DeviceSkuLot {

    @TableId(type = IdType.INPUT)
    private String lotId;

    private String deviceId;

    private String skuId;

    private String batchNo;

    private LocalDate productionDate;

    private LocalDate expiryDate;

    private int quantity;

    private String slotId;

    private String status = "ON_SALE";

    private Instant createdAt;

    private Instant updatedAt;

}
