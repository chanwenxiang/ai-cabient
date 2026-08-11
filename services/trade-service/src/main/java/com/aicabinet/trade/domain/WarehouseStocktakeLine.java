package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 盘点明细。状态：PENDING（未盘）/ MATCHED（账实相符）/ DIFF（有差异）/ ADJUSTED（已调整）。
 */
@TableName("warehouse_stocktake_line")
@Getter
@Setter
public class WarehouseStocktakeLine {

    @TableId(type = IdType.AUTO)
    private Long lineId;
    private Long stocktakeId;
    private String skuId;
    private String batchNo;
    private LocalDate productionDate;
    private LocalDate expiryDate;
    private int bookQty;
    private Integer countedQty;
    private int diffQty;
    private String status = "PENDING";
    private String notes;
    private Instant adjustedAt;

}
