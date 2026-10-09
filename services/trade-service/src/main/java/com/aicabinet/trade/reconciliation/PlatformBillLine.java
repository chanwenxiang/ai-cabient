package com.aicabinet.trade.reconciliation;

import java.time.Instant;

/**
 * 平台账单单行。CB-020③：{@code feeCents} 为渠道<b>实收手续费</b>（分），
 * 来源于微信交易账单「手续费」列（0 基第 22 列，官方 27 列 ALL 型账单）。
 * <p>{@code null} = 该通道账单不提供手续费（支付宝解析器未映射、Mock 账单为账本模拟无费用概念），
 * 上层据此区分「无手续费数据」与「手续费为 0」，不做估算兜底污染。
 */
public record PlatformBillLine(
        String platformTradeNo,
        String merchantOrderNo,
        long amountCents,
        Instant tradeTime,
        String tradeType,
        Long feeCents,
        String rawDetail
) {}
