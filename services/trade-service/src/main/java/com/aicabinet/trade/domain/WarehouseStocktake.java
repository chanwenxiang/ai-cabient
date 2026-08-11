package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * 整仓盘点单。状态：DRAFT / IN_PROGRESS / COMPLETED / ADJUSTED / CANCELLED。
 * 模式：OPEN=明盘（预填账面数）、BLIND=盲盘（实盘数留空）。
 */
@TableName("warehouse_stocktake")
@Getter
@Setter
public class WarehouseStocktake {

    @TableId(type = IdType.AUTO)
    private Long stocktakeId;
    private String stocktakeNo;
    private String warehouseId;
    private String mode = "OPEN";
    private String status = "DRAFT";
    private int bookQty;
    private int countedQty;
    private int diffQty;
    private int diffLineCount;
    private Long operatorId;
    private String notes;
    private Instant createdAt;
    private Instant startedAt;
    private Instant completedAt;

}
