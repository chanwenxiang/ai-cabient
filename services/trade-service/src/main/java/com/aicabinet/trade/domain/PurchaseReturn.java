package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("purchase_return")
@Getter
@Setter
public class PurchaseReturn {
    @TableId(type = IdType.AUTO)
    private Long returnId;
    private Long purchaseOrderId;
    private String warehouseId;
    private String supplierId;
    private String status = "COMPLETED";
    private String notes;

    /**
     * V318：退货原因分类（受控枚举，语义与 {@code WriteOffReasonCategory} 对齐）。
     * <p>NULL = 未分类（要治理的问题），<b>不等于</b> OTHER。
     */
    private String reasonCategory;

    /**
     * V318：责任方。🔴 <b>null ≠ NONE</b>：null = 尚未认定（没人查过），
     * NONE = 已认定「无责任方」（自然滞销等）。追责只能对前者发起。
     */
    private String responsibleParty;

    /**
     * V318：是否残次品（商品本身有问题⇒ 找供应商理赔）。
     * <p>null = 未标记，能与「确认不是残次」区分开。
     */
    private Boolean defectiveFlag;
    private Long operatorId;
    private Instant createdAt;

}
