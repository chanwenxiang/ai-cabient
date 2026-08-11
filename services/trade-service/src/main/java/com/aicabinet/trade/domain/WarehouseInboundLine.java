package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName("warehouse_inbound_line")
@Getter
@Setter
public class WarehouseInboundLine {

    @TableId(type = IdType.AUTO)
    private Long lineId;

    private Long inboundId;

    private String skuId;

    private String batchNo;

    private LocalDate productionDate;

    private LocalDate expiryDate;

    private int quantity;

    private int unitCostCents;

}
