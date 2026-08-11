package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/** 货位库存行：货位 + 商品 + 批次。 */
@TableName("warehouse_bin_stock")
@Getter
@Setter
public class WarehouseBinStock {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long binId;
    private String skuId;
    private String batchNo;
    private LocalDate productionDate;
    private LocalDate expiryDate;
    private int quantity;
    private Instant updatedAt;

}
