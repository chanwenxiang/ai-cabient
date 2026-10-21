package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

@TableName("purchase_order_line")
@Getter
@Setter
public class PurchaseOrderLine {
    @TableId(type = IdType.AUTO)
    private Long lineId;

    private Long purchaseOrderId;
    private String skuId;
    private String batchNo;
    private LocalDate productionDate;
    private LocalDate expiryDate;
    private int orderedQty;
    private int receivedQty;
    private int returnedQty;
    private int unitCostCents;

    /** 件装数快照（CB-061）：下单时的每件支数，防止后续修改 SKU 主数据导致历史订单换算错乱。 */
    private int packQty = 1;

    private String qualityStatus = "PENDING";
    private String qualityNote;
    private int rejectedQty;

}
