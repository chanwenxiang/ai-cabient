package com.aicabinet.common.dto;

import java.util.List;

/**
 * 商户账单（月×设备，图4 对照，叮当小柜商户版账单页，2026-10-15）。
 * 口径=销售报表口径（与月度结算单的分账口径并存，不互相替代）：
 *   销售额 = cabinet_order_line 行金额合计（状态≠FAILED）
 *   销售量 = 明细件数合计
 *   即时退款 = 订单退款中走微信/支付宝原路的部分（按订单 pay_channel 归桶）
 *   余额退款 = 订单退款中走储值余额的部分
 *   流量费   = device_data_fee_bill 该月非 VOID 出账合计（设备筛选时仅该设备）
 *   利润额   = 销售额 − 退款合计 − 商品成本 − 流量费（老系统账单页同构；
 *              老系统样例 1383−13−126−成本=681.5，成本口径一致可对上）
 */
public record MerchantMonthlyBillDto(
        String month,
        String deviceId,
        String deviceName,
        int orderCount,
        long salesCents,
        int salesQty,
        long instantRefundCents,
        long balanceRefundCents,
        long refundedCents,
        long cogsCents,
        long dataFeeCents,
        long profitCents,
        List<MerchantBillDayDto> days
) {}
