package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/** 供应商付款记录。 */
@TableName("supplier_payment")
@Getter
@Setter
public class SupplierPayment {

    @TableId(type = IdType.AUTO)
    private Long paymentId;
    private String supplierId;
    private Long payableId;
    private long amountCents;
    private Long operatorId;
    private String notes;
    private String idempotencyKey;
    private Instant createdAt;

}
