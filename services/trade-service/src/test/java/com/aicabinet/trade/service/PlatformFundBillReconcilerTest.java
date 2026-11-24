package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PlatformFundAccount;
import com.aicabinet.trade.domain.PlatformFundFlow;
import com.aicabinet.trade.mapper.PlatformFundAccountMapper;
import com.aicabinet.trade.mapper.PlatformFundFlowMapper;
import com.aicabinet.trade.reconciliation.WeChatFundBillCsvParser;
import com.aicabinet.trade.reconciliation.WeChatFundBillService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformFundBillReconcilerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 23);
    private static final Instant NOW = Instant.parse("2026-11-24T02:30:00Z");

    private WeChatFundBillService fundBillService;
    private PlatformFundAccountMapper accountMapper;
    private PlatformFundFlowMapper flowMapper;
    private OpsAlertDispatcher alertDispatcher;
    private PlatformFundBillReconciler reconciler;

    @BeforeEach
    void setUp() {
        fundBillService = mock(WeChatFundBillService.class);
        accountMapper = mock(PlatformFundAccountMapper.class);
        flowMapper = mock(PlatformFundFlowMapper.class);
        alertDispatcher = mock(OpsAlertDispatcher.class);
        reconciler = new PlatformFundBillReconciler(fundBillService, accountMapper, flowMapper,
                alertDispatcher, Clock.fixed(NOW, ZoneId.of("Asia/Shanghai")), null);
        ReflectionTestUtils.setField(reconciler, "self", reconciler);
    }

    @Test
    void differencePostsAuditedAdjustment() {
        PlatformFundAccount account = account(100L);
        when(accountMapper.findByChannel("微信")).thenReturn(Optional.of(account));
        when(fundBillService.fetchOperatingAccount(DATE)).thenReturn(
                new WeChatFundBillCsvParser.FundBill(DATE, List.of(), 90L, 0L));
        when(flowMapper.countByRefTypeAndRefId("WECHAT_FUND_BILL", "2026-11-23")).thenReturn(0L);
        when(flowMapper.sumAmountByAccountIdBetween(eq(1L), any(), any())).thenReturn(20L);
        when(accountMapper.findById(1L)).thenReturn(Optional.of(account));
        when(accountMapper.adjustBalance(1L, 10L)).thenReturn(true);

        PlatformFundBillReconciler.ReconResult result = reconciler.reconcile(DATE);

        assertTrue(result.adjusted());
        assertEquals(10L, result.diffCents());
        ArgumentCaptor<PlatformFundFlow> flow = ArgumentCaptor.forClass(PlatformFundFlow.class);
        verify(flowMapper).insert(flow.capture());
        assertEquals(PlatformFundFlow.TYPE_ADJUST, flow.getValue().getFlowType());
        assertEquals(10L, flow.getValue().getAmountCents());
        assertEquals("2026-11-23", flow.getValue().getRefId());
        verify(alertDispatcher).trySend(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void sameDateDoesNotAdjustTwice() {
        PlatformFundAccount account = account(100L);
        when(accountMapper.findByChannel("微信")).thenReturn(Optional.of(account));
        when(fundBillService.fetchOperatingAccount(DATE)).thenReturn(
                new WeChatFundBillCsvParser.FundBill(DATE, List.of(), 90L, 0L));
        when(flowMapper.countByRefTypeAndRefId("WECHAT_FUND_BILL", "2026-11-23")).thenReturn(1L);

        PlatformFundBillReconciler.ReconResult result = reconciler.reconcile(DATE);

        assertEquals(false, result.adjusted());
        verify(accountMapper, never()).adjustBalance(anyLong(), anyLong());
        verify(flowMapper, never()).insert(any());
    }

    private PlatformFundAccount account(long balance) {
        PlatformFundAccount account = new PlatformFundAccount();
        account.setAccountId(1L);
        account.setChannel("微信");
        account.setBalanceCents(balance);
        account.setStatus("ACTIVE");
        return account;
    }
}
