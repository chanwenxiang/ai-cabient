package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.CommissionClawback;
import com.aicabinet.trade.domain.LineCommissionDaily;
import com.aicabinet.trade.domain.LineManager;
import com.aicabinet.trade.domain.StaffCommissionDaily;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.CommissionClawbackMapper;
import com.aicabinet.trade.mapper.LineCommissionDailyMapper;
import com.aicabinet.trade.mapper.LineManagerMapper;
import com.aicabinet.trade.mapper.StaffCommissionDailyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 佣金随退款冲正口径（CB-040，2026-10-14 拍板）：日批重算差额、比例/公式冲回、
 * RESTOCK_QTY 不冲、余额不足挂负（钱包方法允许负数由钱包侧语义保证）。
 */
class CommissionClawbackServiceTest {

    private LineCommissionDailyMapper lineCommissionDailyMapper;
    private StaffCommissionDailyMapper staffCommissionDailyMapper;
    private CommissionClawbackMapper clawbackMapper;
    private CabinetOrderMapper orderMapper;
    private LineManagerMapper managerMapper;
    private LineWalletService lineWalletService;
    private StaffWalletService staffWalletService;
    private CommissionClawbackService service;

    @BeforeEach
    void setUp() {
        lineCommissionDailyMapper = mock(LineCommissionDailyMapper.class);
        staffCommissionDailyMapper = mock(StaffCommissionDailyMapper.class);
        clawbackMapper = mock(CommissionClawbackMapper.class);
        orderMapper = mock(CabinetOrderMapper.class);
        managerMapper = mock(LineManagerMapper.class);
        lineWalletService = mock(LineWalletService.class);
        staffWalletService = mock(StaffWalletService.class);
        lenient().when(clawbackMapper.selectOne(any())).thenReturn(null);
        lenient().when(lineWalletService.debitIfAbsentAllowNegative(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(true);
        lenient().when(staffWalletService.debitIfAbsentAllowNegative(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(true);
        var txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        lenient().when(txManager.getTransaction(any())).thenReturn(null);
        service = new CommissionClawbackService(lineCommissionDailyMapper, staffCommissionDailyMapper,
                clawbackMapper, orderMapper, managerMapper, lineWalletService, staffWalletService,
                new org.springframework.transaction.support.TransactionTemplate(txManager));
    }

    private CabinetOrder paidOrder(int amountCents) {
        CabinetOrder o = new CabinetOrder();
        o.setStatus("PAID");
        o.setTotalAmountCents(amountCents);
        return o;
    }

    private LineCommissionDaily managerRow(long gmv, int count, long commission) {
        LineCommissionDaily row = new LineCommissionDaily();
        row.setManagerId(1L);
        row.setDeviceId("D1");
        row.setBizDate(LocalDate.now().minusDays(2));
        row.setGmvCents(gmv);
        row.setOrderCount(count);
        row.setCommissionCents(commission);
        row.setStatus("POSTED");
        return row;
    }

    @Test
    void managerRowClawsBackGmvDeltaByCurrentBps() {
        when(lineCommissionDailyMapper.selectList(any()))
                .thenReturn(List.of(managerRow(10_000L, 2, 500L)));
        // 退款后剩余 PAID 订单 GMV 6000：差额 4000，费率 500bps → 冲回 200
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(paidOrder(3_000), paidOrder(3_000)));
        LineManager manager = new LineManager();
        manager.setManagerId(1L);
        manager.setCommissionRateBps(500);
        manager.setCommissionFixedCents(0);
        when(managerMapper.findById(1L)).thenReturn(Optional.of(manager));

        int events = service.reverseRecentRefunds();

        assertEquals(1, events);
        ArgumentCaptor<CommissionClawback> captor = ArgumentCaptor.forClass(CommissionClawback.class);
        verify(clawbackMapper).insert(captor.capture());
        assertEquals(200L, captor.getValue().getClawbackCents());
        assertEquals(4_000L, captor.getValue().getRefundGmvCents());
        verify(lineWalletService).debitIfAbsentAllowNegative(eq(1L), eq(200L),
                eq("COMMISSION_CLAWBACK"), eq("COMMISSION_CLAWBACK"), anyString(), anyString());
    }

    @Test
    void noClawbackWhenGmvUnchanged() {
        when(lineCommissionDailyMapper.selectList(any()))
                .thenReturn(List.of(managerRow(6_000L, 1, 300L)));
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(paidOrder(6_000)));
        when(managerMapper.findById(1L)).thenReturn(Optional.empty());

        assertEquals(0, service.reverseRecentRefunds());
        verify(clawbackMapper, never()).insert(any(CommissionClawback.class));
        verify(lineWalletService, never()).debitIfAbsentAllowNegative(anyLong(), anyLong(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void staffGmvRowClawsBackProportionally() {
        StaffCommissionDaily row = new StaffCommissionDaily();
        row.setStaffUserId(9L);
        row.setDeviceId("D1");
        row.setBizDate(LocalDate.now().minusDays(2));
        row.setCommissionCents(300L);
        row.setGmvCents(10_000L);
        row.setCommissionBasis("GMV");
        row.setStatus("POSTED");
        when(staffCommissionDailyMapper.selectList(any())).thenReturn(List.of(row));
        when(lineCommissionDailyMapper.selectList(any())).thenReturn(List.of());
        // 一半 GMV 退款 → 按比例冲回 300×5000/10000 = 150
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(paidOrder(5_000)));

        assertEquals(1, service.reverseRecentRefunds());
        verify(staffWalletService).debitIfAbsentAllowNegative(eq(9L), eq(150L),
                eq("COMMISSION_CLAWBACK"), eq("COMMISSION_CLAWBACK"), anyString(), anyString());
    }

    @Test
    void restockQtyRowNeverClawedBack() {
        StaffCommissionDaily row = new StaffCommissionDaily();
        row.setStaffUserId(9L);
        row.setDeviceId("D1");
        row.setBizDate(LocalDate.now().minusDays(2));
        row.setCommissionCents(300L);
        row.setGmvCents(0L);
        row.setCommissionBasis("RESTOCK_QTY");
        row.setStatus("POSTED");
        when(staffCommissionDailyMapper.selectList(any())).thenReturn(List.of(row));
        when(lineCommissionDailyMapper.selectList(any())).thenReturn(List.of());

        assertEquals(0, service.reverseRecentRefunds());
        verify(staffWalletService, never()).debitIfAbsentAllowNegative(anyLong(), anyLong(),
                anyString(), anyString(), anyString(), anyString());
    }
}
