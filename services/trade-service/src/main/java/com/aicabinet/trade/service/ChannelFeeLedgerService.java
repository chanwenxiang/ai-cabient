package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.PaymentOperationTypes;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.util.BizIds;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

@Service
public class ChannelFeeLedgerService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final PaymentOperationMapper paymentOperationMapper;
    private final SystemConfigService systemConfigService;

    public ChannelFeeLedgerService(PaymentOperationMapper paymentOperationMapper,
                                   SystemConfigService systemConfigService) {
        this.paymentOperationMapper = paymentOperationMapper;
        this.systemConfigService = systemConfigService;
    }

    public record FeePosting(long amountCents, String source) {}

    /**
     * 把日渠道手续费写成平台支出流水；账单实结优先，无账单费用列时按正数毛额估算。
     * 重跑只补差额，不删除已落账痕迹，保证 payment_operation 汇总始终等于最新实结/估算。
     */
    public FeePosting recordDailyFee(LocalDate date, String channel, Long actualFeeCents, long grossCents) {
        String normalized = channel == null ? "" : channel.trim().toUpperCase(Locale.ROOT);
        if (!"WECHAT".equals(normalized) && !"ALIPAY".equals(normalized)) {
            return new FeePosting(0L, "NONE");
        }
        String source = actualFeeCents == null ? "ESTIMATED" : "ACTUAL";
        long desired = actualFeeCents != null
                ? actualFeeCents
                : FundBillService.estimateChannelFeeCents(
                        Math.max(0L, grossCents), FundBillService.resolveChannelFeeBps(systemConfigService));
        Instant start = date.atStartOfDay(ZONE).toInstant();
        Instant end = date.plusDays(1).atStartOfDay(ZONE).toInstant();
        long existing = paymentOperationMapper.sumChannelFeeBetween(start, end, normalized);
        long delta = desired - existing;
        if (delta != 0L) {
            boolean expense = delta > 0L;
            long amount = Math.abs(delta);
            String idempotencyKey = existing == 0L && expense
                    ? "channel-fee:" + normalized + ":" + date
                    : "channel-fee-adjust:" + normalized + ":" + date + ":" + existing + "->" + desired;
            PaymentOperation row = new PaymentOperation();
            row.setOperationId(BizIds.nextNumeric());
            row.setOperationType(expense
                    ? PaymentOperationTypes.CHANNEL_FEE
                    : PaymentOperationTypes.CHANNEL_FEE_REVERSAL);
            row.setAmountCents(Math.toIntExact(amount));
            row.setChannel(normalized);
            row.setStatus("COMPLETED");
            row.setIdempotencyKey(idempotencyKey);
            row.setReason("渠道手续费日结（" + source + "，重跑差额冲正）");
            row.setCreatedAt(date.atTime(12, 0).atZone(ZONE).toInstant());
            paymentOperationMapper.insert(row);
        }
        return new FeePosting(desired, source);
    }
}
