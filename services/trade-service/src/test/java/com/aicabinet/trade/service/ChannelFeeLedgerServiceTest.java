package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.PaymentOperationTypes;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
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
        assertTrue(row.getIdempotencyKey().startsWith("channel-fee:WECHAT:2026-11-24:"));
        assertEquals(row.getOperationId(), row.getIdempotencyKey().substring(
                row.getIdempotencyKey().lastIndexOf(':') + 1));
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
        assertTrue(row.getIdempotencyKey().startsWith("channel-fee:WECHAT:2026-11-24:"));
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
    void feeCycleZeroThenPositive_usesDistinctIdempotencyKeys() {
        when(paymentOperationMapper.sumChannelFeeBetween(START, END, "WECHAT"))
                .thenReturn(0L, 14L, 0L);

        service.recordDailyFee(DATE, "WECHAT", 14L, 2300L);
        service.recordDailyFee(DATE, "WECHAT", 0L, 2300L);
        service.recordDailyFee(DATE, "WECHAT", 12L, 2300L);

        ArgumentCaptor<PaymentOperation> captor = ArgumentCaptor.forClass(PaymentOperation.class);
        verify(paymentOperationMapper, org.mockito.Mockito.times(3)).insert(captor.capture());
        List<String> keys = captor.getAllValues().stream()
                .map(PaymentOperation::getIdempotencyKey)
                .toList();
        assertNotEquals(keys.get(0), keys.get(2));
    }

    @Test
    void rerunWithUnchangedFeeDoesNotInsertDuplicate() {
        when(paymentOperationMapper.sumChannelFeeBetween(START, END, "WECHAT")).thenReturn(14L);

        ChannelFeeLedgerService.FeePosting posting = service.recordDailyFee(DATE, "WECHAT", 14L, 2300L);

        assertEquals(14L, posting.amountCents());
        verify(paymentOperationMapper, never()).insert(any());
    }

    @Test
    void dailyFeeLock_precedesSumAndInsertInsideTransaction() throws Exception {
        when(paymentOperationMapper.sumChannelFeeBetween(START, END, "WECHAT")).thenReturn(0L);

        service.recordDailyFee(DATE, "WECHAT", 14L, 2300L);

        InOrder order = inOrder(paymentOperationMapper);
        order.verify(paymentOperationMapper).lockChannelFeeDay("2026-11-24", "WECHAT");
        order.verify(paymentOperationMapper).sumChannelFeeBetween(START, END, "WECHAT");
        order.verify(paymentOperationMapper).insert(any(PaymentOperation.class));
        assertNotNull(ChannelFeeLedgerService.class
                .getMethod("recordDailyFee", LocalDate.class, String.class, Long.class, long.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class));
        org.apache.ibatis.annotations.Select lockSql = PaymentOperationMapper.class
                .getMethod("lockChannelFeeDay", String.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Select.class);
        assertNotNull(lockSql);
        assertTrue(lockSql.value()[0].contains("pg_advisory_xact_lock"));
        assertTrue(lockSql.value()[0].contains("#{date}"));
        assertTrue(lockSql.value()[0].contains("#{channel}"));
    }

    @Test
    void mockChannelDoesNotCreateRealMoneyLedger() {
        ChannelFeeLedgerService.FeePosting posting = service.recordDailyFee(DATE, "MOCK", null, 2300L);

        assertEquals(0L, posting.amountCents());
        assertEquals("NONE", posting.source());
        verify(paymentOperationMapper, never()).lockChannelFeeDay(anyString(), anyString());
        verify(paymentOperationMapper, never()).sumChannelFeeBetween(any(), any(), anyString());
        verify(paymentOperationMapper, never()).insert(any());
    }

    private PaymentOperation captureInsert() {
        ArgumentCaptor<PaymentOperation> captor = ArgumentCaptor.forClass(PaymentOperation.class);
        verify(paymentOperationMapper).insert(captor.capture());
        return captor.getValue();
    }
}
