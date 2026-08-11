package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import lombok.Data;

@Data
@TableName("cabinet_order_line")
public class CabinetOrderLine {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("order_id")
    private String orderId;
    private String skuId;

    private String skuName;

    private int quantity;

    private int unitPriceCents;

    private int lineAmountCents;

    private Float confidence;

    private String batchNo;

    /** 商品取自的货道（结算时按 SKU 唯一绑定推断；多货道同 SKU 时为空） */
    private String slotId;

    private Integer unitCostCents;

}
