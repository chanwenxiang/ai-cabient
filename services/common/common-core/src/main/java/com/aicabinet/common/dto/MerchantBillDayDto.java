package com.aicabinet.common.dto;

/**
 * 商户账单「按日明细」行（图4 对照，叮当小柜商户版账单页，2026-10-15 用户拍板新页实现）。
 * 口径=销售报表口径：销售额=行金额合计；销售量=件数合计；退款按订单 pay_channel 拆
 * 即时（微信/支付宝原路）与余额（储值余额）两桶；日粒度不含流量费（流量费按月出账）。
 */
public record MerchantBillDayDto(
        String day,
        int orderCount,
        long salesCents,
        int salesQty,
        long instantRefundCents,
        long balanceRefundCents,
        long profitCents
) {}
