package com.aicabinet.trade.domain;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@TableName("inventory_write_off")
@Getter
@Setter
public class InventoryWriteOff {

    @TableId(type = IdType.AUTO)
    private Long writeOffId;

    private String deviceId;

    private String skuId;

    private String batchNo;

    private int quantity;

    private String reason;

    /**
     * V311：原因分类（受控枚举，见 {@code WriteOffReasonCategory}）。
     *
     * <p>🔴 <b>null 与 {@code OTHER} 必须区分</b>：
     * null = <b>未归类</b>（要治理的问题），{@code OTHER} = 运营明确选「其他」。
     * 统计时混为一谈会掩盖「分类缺失」这个数据质量问题。
     */
    private String reasonCategory;

    /**
     * V311：责任方（MERCHANT / SUPPLIER / LOGISTICS / NONE / UNDETERMINED）。
     *
     * <p>🔴 <b>null ≠ {@code NONE}</b>：null = <b>尚未认定</b>（没人查过），
     * {@code NONE} = 已认定「无责任方」（查过了，是自然损耗）。
     * 追责动作只能对前者发起，对后者发起是骚扰。
     */
    private String responsibleParty;

    /** V311：理赔单号（与供应商/物流结算单勾稽）。 */
    private String claimNo;

    /**
     * V311：索赔金额，单位<b>分</b>。
     *
     * <p>与 {@code costCents} <b>不是一回事</b>：cost 是「我们账面损失了多少」，
     * claim 是「向责任方主张多少」。协商折价时两者会不同。
     */
    private Long claimAmountCents;

    private Integer costCents;

    private Long operatorId;

    private Instant createdAt;

}
