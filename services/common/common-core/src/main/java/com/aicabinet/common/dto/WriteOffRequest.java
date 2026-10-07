package com.aicabinet.common.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WriteOffRequest(
        /** V313：设备侧报损时填；与 {@code warehouseId} **恰好填一个**（两边都空/都填都会 400）。 */
        String deviceId,
        /** V313：仓库侧报损时填。仓库里的破损/过期/丢失此前无核销入口。 */
        String warehouseId,
        @NotBlank String skuId,
        String batchNo,
        @NotNull @Min(1) Integer quantity,
        @NotBlank String reason,
        /**
         * V311：原因分类（受控枚举）。
         *
         * <p><b>与 {@code reason} 的区别</b>：{@code reason} 是自由文本（历史字段，
         * UI 现在传 {@code 'EXPIRED'}），无法统计；本字段是受控枚举，才能做
         * 「过期/破损/丢失各占多少」的分类统计与责任判定。
         *
         * <p>⚠️ <b>允许为 null</b>：不强制必填 —— 老调用方（{@code StockHealthView.vue:437}）
         * 只传 reason。加了必填会让它 400，且「强制运营为了过校验而随手选一个分类」
         * 比「没有分类」更坏（假数据比缺数据危险）。
         *
         * <p>取值见 {@code WriteOffReasonCategory}。传 null 时服务端会尝试
         * 从 reason 推断，推断不出就留 null（<b>不猜</b>）。
         */
        String reasonCategory,
        /**
         * V311：责任方（MERCHANT / SUPPLIER / LOGISTICS / NONE / UNDETERMINED）。
         *
         * <p>⚠️ <b>刻意不校验枚举</b>：责任归属是<b>事后认定</b>的 ——
         * 出库时未必知道这批货最后算谁的。强制枚举 + 必填会让历史流程无法写入。
         * 允许 null 表示「尚未认定」，与 {@code NONE}（已认定：无责任方）语义不同。
         */
        String responsibleParty,
        /** V311：理赔单号（与供应商/物流结算单勾稽）。 */
        String claimNo,
        /**
         * V311：索赔金额，单位<b>分</b>。
         *
         * <p>与 {@code costCents}（账面损失成本）<b>不是一回事</b>：
         * cost 是「我们损失了多少」，claim 是「向责任方主张多少」，
         * 两者可以不等（如协商折价）。
         */
        Long claimAmountCents
) {}