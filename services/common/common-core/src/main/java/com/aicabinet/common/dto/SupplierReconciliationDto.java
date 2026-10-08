package com.aicabinet.common.dto;

/**
 * 供应商月度对账单（V326，E3 缺口 #8；行业公式见 CB-016：
 * 期末余额 = 期初余额 + 本期新增应付 − 本期退货冲减 − 本期付款）。
 *
 * <p>金额单位：分（cents）。月份格式：{@code yyyy-MM}。
 *
 * <p>🔴 {@code ledgerConsistent}：流水推算的当前余额（{@code ledgerBalanceCents}）
 * 与主表当前余额（{@code mainBalanceCents}）是否一致。两套实现交叉验证——
 * 不一致说明有人绕过 Service 直改了库或存在未登记的事件，对账单视为不可信，
 * 必须先排查再对账。
 */
public record SupplierReconciliationDto(
        String supplierId,
        String supplierName,
        String month,
        long openingCents,
        long receivedCents,
        long returnedCents,
        long paidCents,
        long closingCents,
        long ledgerBalanceCents,
        long mainBalanceCents,
        boolean ledgerConsistent) {
}
