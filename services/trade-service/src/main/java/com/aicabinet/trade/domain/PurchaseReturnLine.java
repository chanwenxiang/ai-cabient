package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

@TableName("purchase_return_line")
@Getter
@Setter
public class PurchaseReturnLine {
    @TableId(type = IdType.AUTO)
    private Long lineId;
    private Long returnId;
    private Long purchaseLineId;
    private String skuId;
    private String batchNo;
    private int quantity;

}
