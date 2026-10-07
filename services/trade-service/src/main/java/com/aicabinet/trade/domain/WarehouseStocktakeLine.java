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
    /**
     * V312：盘点差异原因分类（受控枚举，见 StocktakeDiffReason）。
     *
     * <p>🔴 与 {@code inventory_write_off.reason_category} 是**两条链路**：
     * 这里记「账实不符的原因」，那里记「主动核销的原因」。语义不同，混用会让统计失真。
     *
     * <p><b>null = 未分类</b>（要治理的问题），不等于 OTHER。
     */
    private String diffReason;

    private String notes;
    private Instant adjustedAt;

}
