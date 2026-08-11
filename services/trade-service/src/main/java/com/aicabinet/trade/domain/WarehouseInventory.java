package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName("warehouse_inventory")
@Getter
@Setter
public class WarehouseInventory {

    @TableId(type = IdType.AUTO)
    private Long inventoryId;

    private String warehouseId;

    private String skuId;

    private String batchNo;

    private LocalDate productionDate;

    private LocalDate expiryDate;

    private int quantity;

    private Instant updatedAt;

}
