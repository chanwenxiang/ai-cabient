package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * 供应商应付账款：每张采购单一条，收货累加金额，退货冲减，付款核销。
 * 状态：UNPAID / PARTIAL / PAID / CLOSED（全额退货冲销）。
 */
@TableName("supplier_payable")
@Getter
@Setter
public class SupplierPayable {

    @TableId(type = IdType.AUTO)
    private Long payableId;
    private String supplierId;
    private Long purchaseOrderId;
    private String warehouseId;
    private long amountCents;
    private long paidAmountCents;
    private String status = "UNPAID";
    private LocalDate dueDate;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
    private Instant paidAt;

}
