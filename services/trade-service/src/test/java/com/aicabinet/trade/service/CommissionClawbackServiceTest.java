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
 * 佣金随退款冲正口径（CB-040，2026-10-14 拍板；CB-056 F-8 2026-10-17 判定费率不追溯）：
 * 日批重算差额、线长/补货员两侧一律按**发佣行快照**比例冲回、RESTOCK_QTY 不冲、
 * 余额不足挂负（钱包方法允许负数由钱包侧语义保证）。
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

    private LineManager manager(int rateBps, int fixedCents) {
        LineManager manager = new LineManager();
        manager.setManagerId(1L);
        manager.setCommissionRateBps(rateBps);
        manager.setCommissionFixedCents(fixedCents);
        return manager;
    }

    @Test
    void managerRowClawsBackByPostedCommissionSnapshot() {
        // F-8（CB-056）：冲回额按**发佣行快照**比例折算 = 500 × 4000/10000 = 200。
        // 行上 gmv=10000 时按 500bps 发的 500 佣金，退款后剩余 PAID GMV 6000 ⇒ 差额 4000。
        // 线长档案里此刻的费率故意填 800bps（发佣后被调高）：若还读实时费率就会冲 320（多扣 120），
        // 现在必须是 200 —— 费率变更不得追溯已经发掉的佣金。
        when(lineCommissionDailyMapper.selectList(any()))
                .thenReturn(List.of(managerRow(10_000L, 2, 500L)));
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(paidOrder(3_000), paidOrder(3_000)));
        when(managerMapper.findById(1L)).thenReturn(Optional.of(manager(800, 50)));

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
    void managerRowClawbackCappedByPostedCommission() {
        // 全额退款 ⇒ 冲回额恰好等于该行已发佣金（200），不会超过；每单固定成分按 GMV 比例追回，
        // 宁可少扣也绝不向线长扣超过他拿到的钱
        when(lineCommissionDailyMapper.selectList(any()))
                .thenReturn(List.of(managerRow(10_000L, 2, 200L)));
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of());
        when(managerMapper.findById(1L)).thenReturn(Optional.of(manager(5_000, 500)));

        assertEquals(1, service.reverseRecentRefunds());
        verify(lineWalletService).debitIfAbsentAllowNegative(eq(1L), eq(200L),
                eq("COMMISSION_CLAWBACK"), eq("COMMISSION_CLAWBACK"), anyString(), anyString());
    }

    @Test
    void managerRowWithoutGmvBaseIsNotClawedBack() {
        // V361 之前的存量行没有 GMV 基数 ⇒ 无法比例折算 ⇒ 保守不冲（与补货员侧同判据）
        LineCommissionDaily row = managerRow(0L, 2, 500L);
        row.setGmvCents(null);
        when(lineCommissionDailyMapper.selectList(any())).thenReturn(List.of(row));
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of());
        when(managerMapper.findById(1L)).thenReturn(Optional.of(manager(500, 0)));

        assertEquals(0, service.reverseRecentRefunds());
        verify(lineWalletService, never()).debitIfAbsentAllowNegative(anyLong(), anyLong(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void deletedManagerRowIsNotClawedBack() {
        // 线长档案已删除 ⇒ 不冲（老口径读不到费率即 gross=0；不新增向已删除人员钱包追扣的路径）
        when(lineCommissionDailyMapper.selectList(any()))
                .thenReturn(List.of(managerRow(10_000L, 2, 500L)));
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(paidOrder(3_000), paidOrder(3_000)));
        when(managerMapper.findById(1L)).thenReturn(Optional.empty());

        assertEquals(0, service.reverseRecentRefunds());
        verify(lineWalletService, never()).debitIfAbsentAllowNegative(anyLong(), anyLong(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void debitSkippedRollsBackClawbackIncrement() {
        // B-1 F-7：钱包把本次负扣判为「已存在同 ref 流水」而跳过时，累计冲回行必须一起回滚，
        // 否则「已冲回额」虚增而钱没扣 ⇒ 差额从此不再被重算（永久少冲）。
        when(lineCommissionDailyMapper.selectList(any()))
                .thenReturn(List.of(managerRow(10_000L, 2, 500L)));
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(paidOrder(3_000), paidOrder(3_000)));
        when(managerMapper.findById(1L)).thenReturn(Optional.of(manager(500, 0)));
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
        // ⇒ GMV 差额 4000，按行快照 500 佣金比例折算 ⇒ 冲回 200。
        when(lineCommissionDailyMapper.selectList(any()))
                .thenReturn(List.of(managerRow(10_000L, 2, 500L)));
        CabinetOrder partial = new CabinetOrder();
        partial.setStatus("PARTIAL_REFUNDED");
        partial.setTotalAmountCents(8_000);
        partial.setRefundedCents(2_000);
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(partial));
        when(managerMapper.findById(1L)).thenReturn(Optional.of(manager(500, 0)));

        int events = service.reverseRecentRefunds();

        assertEquals(1, events);
        ArgumentCaptor<CommissionClawback> captor = ArgumentCaptor.forClass(CommissionClawback.class);
        verify(clawbackMapper).insert(captor.capture());
        assertEquals(200L, captor.getValue().getClawbackCents());
        assertEquals(4_000L, captor.getValue().getRefundGmvCents());
    }

    @Test
    void fullyRefundedOrderStaysOutOfBase() {
        // 全额退款单（REFUNDED）不计基数：发佣行 gmv=6000、当前全为 REFUNDED ⇒ 基数 0 ⇒ 全额冲回 300。
        when(lineCommissionDailyMapper.selectList(any()))
                .thenReturn(List.of(managerRow(6_000L, 1, 300L)));
        CabinetOrder refunded = new CabinetOrder();
        refunded.setStatus("REFUNDED");
        refunded.setTotalAmountCents(6_000);
        refunded.setRefundedCents(6_000);
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(refunded));
        when(managerMapper.findById(1L)).thenReturn(Optional.of(manager(500, 0)));

        assertEquals(1, service.reverseRecentRefunds());
        ArgumentCaptor<CommissionClawback> captor = ArgumentCaptor.forClass(CommissionClawback.class);
        verify(clawbackMapper).insert(captor.capture());
        assertEquals(300L, captor.getValue().getClawbackCents());
        assertEquals(6_000L, captor.getValue().getRefundGmvCents());
    }

    @Test
    void noClawbackWhenGmvUnchanged() {
        when(lineCommissionDailyMapper.selectList(any()))
                .thenReturn(List.of(managerRow(6_000L, 1, 300L)));
        when(orderMapper.findByDeviceIdAndCreatedAtBetween(any(), any(), any()))
                .thenReturn(List.of(paidOrder(6_000)));

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
