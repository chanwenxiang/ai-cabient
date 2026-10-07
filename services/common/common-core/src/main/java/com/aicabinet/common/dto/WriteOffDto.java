package com.aicabinet.common.dto;

import java.time.Instant;

public record WriteOffDto(
        Long writeOffId,
        String deviceId,
        String skuId,
        String batchNo,
        int quantity,
        String reason,
        /** V311：原因分类（受控枚举）。null = 未归类（不等于 {@code OTHER}）。 */
        String reasonCategory,
        /** V311：责任方。null = 尚未认定（与 {@code NONE}「认定无责任方」不同）。 */
        String responsibleParty,
        /** V311：理赔单号。 */
        String claimNo,
        /** V311：索赔金额，单位分。null = 未主张。与 {@code costCents} 不同义。 */
        Long claimAmountCents,
        Integer costCents,
        Long operatorId,
        Instant createdAt
) {}