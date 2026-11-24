package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.PaymentOperationTypes;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelFeeLedgerServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 24);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant START = DATE.atStartOfDay(ZONE).toInstant();
    private static final Instant END = DATE.plusDays(1).atStartOfDay(ZONE).toInstant();

    @Mock private PaymentOperationMapper paymentOperationMapper;
    @Mock private SystemConfigService systemConfigService;

    private ChannelFeeLedgerService service;

    @BeforeEach
    void setUp() {
        service = new ChannelFeeLedgerService(paymentOperationMapper, systemConfigService);
    }

    @Test
    void actualFeePostsExpenseWithoutTouchingUserBalance() {
        when(paymentOperationMapper.sumChannelFeeBetween(START, END, "WECHAT")).thenReturn(0L);

        ChannelFeeLedgerService.FeePosting posting = service.recordDailyFee(DATE, "WECHAT", 14L, 2300L);

        assertEquals(14L, posting.amountCents());
        assertEquals("ACTUAL", posting.source());
        PaymentOperation row = captureInsert();
        assertEquals(PaymentOperationTypes.CHANNEL_FEE, row.getOperationType());
        assertEquals(14, row.getAmountCents());
        assertEquals("WECHAT", row.getChannel());
        assertNull(row.getOrderId(), "平台手续费不对应 cabinet_order，必须保持 NULL 避免外键失败");
        assertEquals("channel-fee:WECHAT:2026-11-24", row.getIdempotencyKey());
        assertEquals(DATE.atTime(12, 0).atZone(ZONE).toInstant(), row.getCreatedAt());
        assertNull(row.getUserId());
        assertNull(row.getBalanceBeforeCents());
        assertNull(row.getBalanceAfterCents());
    }

    @Test
    void missingActualFeeUsesConfiguredEstimate() {
        when(paymentOperationMapper.sumChannelFeeBetween(START, END, "WECHAT")).thenReturn(0L);
        when(systemConfigService.getInt("fund.channel_fee_bps", 60)).thenReturn(60);

        ChannelFeeLedgerService.FeePosting posting = service.recordDailyFee(DATE, "WECHAT", null, 2300L);

        assertEquals(14L, posting.amountCents());
        assertEquals("ESTIMATED", posting.source());
        assertEquals(14, captureInsert().getAmountCents());
    }

    @Test
    void rerunWithHigherActualFeeAppendsPositiveAdjustment() {
        when(paymentOperationMapper.sumChannelFeeBetween(START, END, "WECHAT")).thenReturn(10L);

        service.recordDailyFee(DATE, "WECHAT", 14L, 2300L);

        PaymentOperation row = captureInsert();
        assertEquals(PaymentOperationTypes.CHANNEL_FEE, row.getOperationType());
        assertEquals(4, row.getAmountCents());
        assertTrue(row.getIdempotencyKey().startsWith("channel-fee-adjust:WECHAT:2026-11-24:"));
    }

    @Test
    void rerunWithLowerActualFeeAppendsReversal() {
        when(paymentOperationMapper.sumChannelFeeBetween(START, END, "WECHAT")).thenReturn(14L);

        service.recordDailyFee(DATE, "WECHAT", 12L, 2300L);

        PaymentOperation row = captureInsert();
        assertEquals(PaymentOperationTypes.CHANNEL_FEE_REVERSAL, row.getOperationType());
        assertEquals(2, row.getAmountCents());
    }

    @Test
    void actualZeroReversesEarlierEstimate() {
        when(paymentOperationMapper.sumChannelFeeBetween(START, END, "WECHAT")).thenReturn(14L);

        ChannelFeeLedgerService.FeePosting posting = service.recordDailyFee(DATE, "WECHAT", 0L, 2300L);

        assertEquals(0L, posting.amountCents());
        PaymentOperation row = captureInsert();
        assertEquals(PaymentOperationTypes.CHANNEL_FEE_REVERSAL, row.getOperationType());
        assertEquals(14, row.getAmountCents());
    }

    @Test
    void rerunWithUnchangedFeeDoesNotInsertDuplicate() {
        when(paymentOperationMapper.sumChannelFeeBetween(START, END, "WECHAT")).thenReturn(14L);

        ChannelFeeLedgerService.FeePosting posting = service.recordDailyFee(DATE, "WECHAT", 14L, 2300L);

        assertEquals(14L, posting.amountCents());
        verify(paymentOperationMapper, never()).insert(any());
    }

    @Test
    void mockChannelDoesNotCreateRealMoneyLedger() {
        ChannelFeeLedgerService.FeePosting posting = service.recordDailyFee(DATE, "MOCK", null, 2300L);

        assertEquals(0L, posting.amountCents());
        assertEquals("NONE", posting.source());
        verify(paymentOperationMapper, never()).sumChannelFeeBetween(any(), any(), anyString());
        verify(paymentOperationMapper, never()).insert(any());
    }

    private PaymentOperation captureInsert() {
        ArgumentCaptor<PaymentOperation> captor = ArgumentCaptor.forClass(PaymentOperation.class);
        verify(paymentOperationMapper).insert(captor.capture());
        return captor.getValue();
    }
}
