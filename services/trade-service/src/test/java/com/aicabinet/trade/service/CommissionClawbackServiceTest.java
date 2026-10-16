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
    private org.springframework.transaction.PlatformTransactionManager txManager;
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
        this.txManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
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
    void debitSkippedRollsBackClawbackIncrement() {
        // B-1 F-7：钱包把本次负扣判为「已存在同 ref 流水」而跳过时，累计冲回行必须一起回滚，
        // 否则「已冲回额」虚增而钱没扣 ⇒ 差额从此不再被重算（永久少冲）。
        when(lineCommissionDailyMapper.selectList(any()))
                .thenReturn(List.of(managerRow(10_000L, 2, 500L)));
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(paidOrder(3_000), paidOrder(3_000)));
        LineManager manager = new LineManager();
        manager.setManagerId(1L);
        manager.setCommissionRateBps(500);
        manager.setCommissionFixedCents(0);
        when(managerMapper.findById(1L)).thenReturn(Optional.of(manager));
        when(lineWalletService.debitIfAbsentAllowNegative(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString())).thenReturn(false);

        assertEquals(0, service.reverseRecentRefunds(), "跳过负扣却算成一次冲回事件 ⇒ 台账与钱包口径分叉");
        verify(clawbackMapper).insert(any());
        verify(txManager).rollback(any());
    }

    @Test
    void partialRefundedOrderCountsByNetAmount_notExcluded() {
        // 审计 T2（2026-10-15）：部分退款单不整单排除——按净额（total−refunded）计基数。
        // 发佣行 gmv=10000；当前仅一张 PARTIAL_REFUNDED 单 total=8000/refunded=2000（净 6000）
        // ⇒ 差额 4000（而非旧口径的整单 10000），费率 500bps ⇒ 冲回 200。
        when(lineCommissionDailyMapper.selectList(any()))
                .thenReturn(List.of(managerRow(10_000L, 2, 500L)));
        CabinetOrder partial = new CabinetOrder();
        partial.setStatus("PARTIAL_REFUNDED");
        partial.setTotalAmountCents(8_000);
        partial.setRefundedCents(2_000);
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(partial));
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
    }

    @Test
    void fullyRefundedOrderStaysOutOfBase() {
        // 全额退款单（REFUNDED）不计基数：发佣行 gmv=6000、当前全为 REFUNDED ⇒ 全额冲回。
        when(lineCommissionDailyMapper.selectList(any()))
                .thenReturn(List.of(managerRow(6_000L, 1, 300L)));
        CabinetOrder refunded = new CabinetOrder();
        refunded.setStatus("REFUNDED");
        refunded.setTotalAmountCents(6_000);
        refunded.setRefundedCents(6_000);
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(refunded));
        when(managerMapper.findById(1L)).thenReturn(Optional.empty());

        assertEquals(0, service.reverseRecentRefunds()); // fixed=0 ⇒ gross=0 不冲（差额有但费率空）
        verify(lineWalletService, never()).debitIfAbsentAllowNegative(anyLong(), anyLong(),
                anyString(), anyString(), anyString(), anyString());
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
