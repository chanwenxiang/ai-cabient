package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@TableName("warehouse_transfer_line")
@Getter
@Setter
public class WarehouseTransferLine {
    @TableId(type = IdType.AUTO)
    private Long lineId;
    private Long transferId;
    private String skuId;
    private String batchNo = "";
    private LocalDate expiryDate;
    private int quantity;
}
