package com.aicabinet.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreatePurchaseReturnRequest(
        @NotNull Long purchaseOrderId,
        String notes,
        /**
         * V318：退货原因分类。
         *
         * <p>🔴 <b>刻意不设为必填</b>：采购退货是**唯一能把货退回供应商的动作**，
         * 它的原因直接决定「能不能索赔 / 责任在谁 / 下次还该不该跟这家合作」。
         * 但强制填写会让人为过校验随便选一个 —— **假分类比没分类更坏**。
         *
         * <p>语义与 {@code WriteOffReasonCategory}（V311）**刻意对齐**，
         * 这样「报废原因」在报损链路与退货链路上可合并统计。
         */
        String reasonCategory,
        /**
         * V318：责任方（SUPPLIER / LOGISTICS / MERCHANT / NONE / UNDETERMINED）。
         *
         * <p>🔴 语义区分：{@code null} = <b>尚未认定</b>（没人查过），
         * {@code NONE} = 已认定「无责任方」。追责动作只能对前者发起。
         */
        String responsibleParty,
        /**
         * V318：是否残次品。
         *
         * <p>= true 表示**商品本身有问题**（破损/变质/规格不符）⇒ 该找供应商理赔。
         * false 表示「我方不要了」（如买多了/滞销）⇒ 不涉及索赔。
         *
         * <p>🔴 留空而非默认 false：null = 未标记，能与「确认不是残次」区分开。
         */
        Boolean defective,
        @NotEmpty @Valid List<PurchaseReturnLineRequest> lines
) {
    public record PurchaseReturnLineRequest(
            @NotNull Long purchaseLineId,
            int quantity
    ) {}
}
