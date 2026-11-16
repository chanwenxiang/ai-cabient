package com.aicabinet.common.dto;

import java.util.List;

/**
 * 商户账单（月×设备，图4 对照，叮当小柜商户版账单页，2026-10-15）。
 * 口径=销售报表口径（与月度结算单的分账口径并存，不互相替代）：
 *   销售额 = cabinet_order_line 行金额合计（状态≠FAILED）
 *   销售量 = 明细件数合计
 *   即时退款 = 订单退款中走微信/支付宝原路的部分（按订单 pay_channel 归桶）
 *   余额退款 = 订单退款中走储值余额的部分
 *   流量费   = merchant_fee_ledger 该月 DATA 行合计（日结批真实扣过的钱，MA-272）；
 *              指定设备时退化为 device_data_fee_bill 该设备非 VOID 出账 —— 费用台账只有
 *              「商户×日」粒度、无 device 维度，尚未归集到设备（MA-299）
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
        /**
         * B4：dataFeeCents 的归集口径信号——
         * DEVICE=该柜机逐台台账行合计（可按柜机展示金额）；MERCHANT=商户级聚合（历史月，
         * 前端显示「商户级」）；NONE=当月无费用行。
         */
        String dataFeeBasis,
        long profitCents,
        List<MerchantBillDayDto> days
) {}
