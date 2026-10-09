package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.MerchantSettlementBill;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.MerchantSettlementBillMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CB-020 ②：商户月度结算单。纯函数口径（分组/排除冲正/状态拆分/确定性单号）
 * 负向验证；主流程钉「幂等重建、CONFIRMED 状态保留、陈旧行清理、锁忙不写」。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MerchantSettlementBillRebuildTest {

    static {
        // 服务走 lambda wrapper：纯 Mockito 环境需手动注册两个实体的 TableInfo 缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), OrderRevenueSplit.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), MerchantSettlementBill.class);
    }

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final YearMonth MONTH = YearMonth.of(2026, 9);
    private static final Instant COMPUTED = Instant.parse("2026-10-09T00:00:00Z");

    @Mock private OrderRevenueSplitMapper splitRepository;
    @Mock private MerchantSettlementBillMapper billRepository;
    @Mock private DistributedLockService distributedLockService;

    private MerchantSettlementBillService service;

    @BeforeEach
    void setUp() {
        service = new MerchantSettlementBillService(splitRepository, billRepository, distributedLockService);
        when(distributedLockService.tryLock(
                MerchantSettlementBillService.settlementBillLockKey(MONTH), 120, 5)).thenReturn(true);
        when(splitRepository.selectList(any())).thenReturn(List.of());
        when(billRepository.selectList(any())).thenReturn(List.of());
    }

    private static OrderRevenueSplit split(String merchantId, String status,
                                           long gross, long platform, long merchant) {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setMerchantId(merchantId);
        s.setStatus(status);
        s.setGrossCents(gross);
        s.setPlatformCents(platform);
        s.setMerchantCents(merchant);
        s.setSettlementBatchNo("9001");
        s.setCreatedAt(MONTH.atDay(15).atStartOfDay(ZONE).toInstant());
        return s;
    }

    // —— 纯函数口径（负向验证：冲正剔除/状态拆分必须算得对） ——

    @Test
    void buildRows_groupsByMerchantExcludesReversedAndSplitsStatuses() {
        List<MerchantSettlementBill> rows = MerchantSettlementBillService.buildRows(MONTH, List.of(
                split("M1", "SUCCESS", 1000, 100, 900),
                split("M1", "LEDGER_ONLY", 500, 50, 450),
                split("M1", "WECHAT_FAILED", 200, 20, 180),
                split("M1", "VOIDED", 999, 99, 900),        // 退款冲正：整行剔除
                split("M2", "SUCCESS", 3000, 600, 2400),
                split(null, "SUCCESS", 1, 0, 1)             // 无商户：剔除
        ), COMPUTED);

        assertEquals(2, rows.size());
        MerchantSettlementBill m1 = rows.get(0);
        assertEquals("M1", m1.getMerchantId());
        assertEquals(3, m1.getOrderCount());
        assertEquals(1700, m1.getGrossCents());
        assertEquals(170, m1.getPlatformCents());
        assertEquals(1530, m1.getMerchantCents());
        assertEquals(900, m1.getSettledCents());       // 仅 SUCCESS
        assertEquals(630, m1.getPendingCents());       // LEDGER_ONLY + WECHAT_FAILED
        assertEquals(1, m1.getFailedCount());          // 只数 WECHAT_FAILED/FAILED
        assertEquals("SB202609-M1", m1.getBillNo());   // 确定性单号
        assertEquals(COMPUTED, m1.getComputedAt());
        MerchantSettlementBill m2 = rows.get(1);
        assertEquals("M2", m2.getMerchantId());
        assertEquals(2400, m2.getSettledCents());
        assertEquals("SB202609-M2", m2.getBillNo());
    }

    @Test
    void buildRows_statusCaseInsensitive() {
        List<MerchantSettlementBill> rows = MerchantSettlementBillService.buildRows(MONTH, List.of(
                split("M1", "success", 100, 10, 90),
                split("M1", "voided", 500, 50, 450)
        ), COMPUTED);
        assertEquals(1, rows.size());
        assertEquals(1, rows.get(0).getOrderCount());
        assertEquals(90, rows.get(0).getSettledCents());
        assertEquals(0, rows.get(0).getPendingCents());
    }

    // —— 主流程 ——

    @Test
    void rebuildMonth_insertsRowsForNewMerchants() {
        when(splitRepository.selectList(any())).thenReturn(List.of(
                split("M1", "SUCCESS", 1000, 100, 900),
                split("M2", "LEDGER_ONLY", 500, 50, 450)));
        when(billRepository.findByMerchantAndPeriod(any(), any())).thenReturn(null);

        int written = service.rebuildMonth(MONTH);

        assertEquals(2, written);
        ArgumentCaptor<MerchantSettlementBill> captor = ArgumentCaptor.forClass(MerchantSettlementBill.class);
        verify(billRepository, times(2)).insert(captor.capture());
        assertEquals(2, captor.getAllValues().size());
        verify(billRepository, never()).update(any(MerchantSettlementBill.class), any());
    }

    @Test
    void rebuildMonth_updatesExistingAndKeepsConfirmedStatus() {
        when(splitRepository.selectList(any())).thenReturn(List.of(
                split("M1", "SUCCESS", 1000, 100, 900)));
        MerchantSettlementBill existing = new MerchantSettlementBill();
        existing.setMerchantId("M1");
        existing.setPeriodMonth(MONTH.atDay(1));
        existing.setBillNo("SB202609-M1");
        existing.setStatus("CONFIRMED");
        Instant confirmedAt = Instant.parse("2026-10-01T00:00:00Z");
        existing.setConfirmedAt(confirmedAt);
        existing.setGrossCents(1);
        when(billRepository.findByMerchantAndPeriod("M1", MONTH.atDay(1))).thenReturn(existing);

        int written = service.rebuildMonth(MONTH);

        assertEquals(1, written);
        verify(billRepository, never()).insert(any(MerchantSettlementBill.class));
        ArgumentCaptor<MerchantSettlementBill> captor = ArgumentCaptor.forClass(MerchantSettlementBill.class);
        verify(billRepository).update(captor.capture(), any());
        MerchantSettlementBill saved = captor.getValue();
        // 金额刷新、状态与确认时间保留（CONFIRMED 不被重置回 PENDING）
        assertEquals(1000, saved.getGrossCents());
        assertEquals("CONFIRMED", saved.getStatus());
        assertEquals(confirmedAt, saved.getConfirmedAt());
        assertNotNull(saved.getComputedAt());
    }

    @Test
    void rebuildMonth_deletesStaleRowsWithoutCurrentSplits() {
        when(splitRepository.selectList(any())).thenReturn(List.of(
                split("M1", "SUCCESS", 1000, 100, 900)));
        when(billRepository.findByMerchantAndPeriod(any(), any())).thenReturn(null);
        MerchantSettlementBill stale = new MerchantSettlementBill();
        stale.setMerchantId("M3");
        stale.setPeriodMonth(MONTH.atDay(1));
        when(billRepository.selectList(any())).thenReturn(List.of(stale));

        service.rebuildMonth(MONTH);

        // any(Wrapper.class) 消歧：BaseTradeMapper 自定义了 delete(T)，裸 any() 无法在两个重载间选择
        verify(billRepository).delete(any(Wrapper.class));
    }

    @Test
    void rebuildMonth_lockBusy_returnsZeroWithoutWrite() {
        when(distributedLockService.tryLock(
                MerchantSettlementBillService.settlementBillLockKey(MONTH), 120, 5)).thenReturn(false);

        int written = service.rebuildMonth(MONTH);

        assertEquals(0, written);
        verify(billRepository, never()).insert(any(MerchantSettlementBill.class));
        verify(billRepository, never()).update(any(MerchantSettlementBill.class), any());
    }
}
