package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FundDailyBillDto;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.domain.PaymentReconciliation;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.FinanceMarginDailyLockMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.mapper.PaymentReconciliationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * V309：资金看板「通道费」费率改为运营台可配（原先硬编码 {@code 0.006}）。
 *
 * <p>🔴 <b>为什么这值得测</b>：这个数字<b>直接乘在商户结算金额上</b>。硬编码时运营无法按实际
 * 签约费率校正；改成可配后，误填的风险从「代码里改」变成「运营手填」——
 * 所以<b>钳制与回落</b>比「读到正确的值」更重要，必须证明它们真的会生效。
 */
@ExtendWith(MockitoExtension.class)
class FundBillChannelFeeRateTest {

    @Mock private OrderRevenueSplitMapper splitMapper;
    @Mock private DeviceInfoMapper deviceInfoMapper;
    @Mock private MerchantMapper merchantMapper;
    @Mock private FinanceMarginDailyLockMapper marginLockMapper;
    @Mock private CabinetOrderMapper orderMapper;
    @Mock private CabinetOrderLineMapper lineMapper;
    @Mock private InventoryWriteOffMapper writeOffMapper;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private PermissionService permissionService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private SystemConfigService systemConfigService;
    @Mock private PaymentReconciliationMapper reconMapper;

    private FundBillService service;

    @BeforeEach
    void setUp() {
        service = new FundBillService(splitMapper, deviceInfoMapper, merchantMapper,
                marginLockMapper, reconMapper, orderMapper, lineMapper, writeOffMapper,
                merchantScopeService, permissionService, distributedLockService,
                systemConfigService, null);
        // lenient：反射用例（noNegativeFee_edgeCases）不经过 service 主流程
        lenient().doNothing().when(permissionService).requireAnyPermission(any(), any(), any());
        lenient().when(merchantScopeService.allowedDeviceIds(any())).thenReturn(null);
        Merchant m = new Merchant();
        m.setMerchantId("M-1");
        m.setMerchantName("测试商户");
        lenient().when(merchantMapper.findAll()).thenReturn(List.of(m));
    }

    /** 一条 gross=10000 分（¥100）的分账记录。 */
    private OrderRevenueSplit split() {
        OrderRevenueSplit s = new OrderRevenueSplit();
        s.setOrderId("O-1");
        s.setDeviceId("D-1");
        s.setMerchantId("M-1");
        s.setGrossCents(10_000L);
        s.setPlatformCents(0L);
        s.setMerchantCents(10_000L);
        s.setStatus("LEDGER_ONLY");
        s.setCreatedAt(java.time.Instant.parse("2026-08-15T02:00:00Z"));
        return s;
    }

    private long channelFeeOfConfiguredBps(int bps) {
        when(systemConfigService.getInt("fund.channel_fee_bps", 60)).thenReturn(bps);
        when(splitMapper.selectList(any())).thenReturn(List.of(split()));
        var page = service.listLedger(1L, "2026-08-01", "2026-08-31", null, null, null, 0, 100);
        return page.items().stream()
                .filter(r -> "CHANNEL_FEE".equals(r.financialType()))
                .mapToLong(r -> r.amountCents())
                .findFirst()
                .orElse(-1L);
    }

    @Test
    @DisplayName("默认 60bps：gross ¥100 ⇒ 通道费 6 分")
    void defaultBps_appliesSixty() {
        // 10000 * 60 / 10000 = 60 分。0.6% of ¥100 = ¥0.6 = 60 分
        assertEquals(60L, channelFeeOfConfiguredBps(60));
    }

    @Test
    @DisplayName("运营改成 100bps 后费率真的跟着变（证明配置真的接上了）")
    void configuredBps_isHonoured() {
        // 10000 * 100 / 10000 = 100 分 = ¥1（1% of ¥100）
        assertEquals(100L, channelFeeOfConfiguredBps(100));
    }

    @Test
    @DisplayName("bps = 0 ⇒ 不生成 CHANNEL_FEE 行（不是生成 0 元噪音行）")
    void zeroBps_skipsRow() {
        assertEquals(-1L, channelFeeOfConfiguredBps(0));
    }

    @Test
    @DisplayName("超界值（>1000）回落默认 60，不让误填打穿看板")
    void outOfRange_fallsBackToDefault() {
        // 100000 bps = 1000% —— 明显误填。回落 60 ⇒ 60 分
        assertEquals(60L, channelFeeOfConfiguredBps(100_000));
    }

    @Test
    @DisplayName("负值回落默认（负通道费会让平台收入倒挂为正）")
    void negative_fallsBackToDefault() {
        assertEquals(60L, channelFeeOfConfiguredBps(-50));
    }

    @Test
    @DisplayName("systemConfigService 为 null（测试/降级场景）⇒ 回落默认，不 NPE")
    void nullConfigService_fallsBackToDefault() {
        FundBillService noConfig = new FundBillService(splitMapper, deviceInfoMapper, merchantMapper,
                marginLockMapper, null, orderMapper, lineMapper, writeOffMapper,
                merchantScopeService, permissionService, distributedLockService, null, null);
        when(splitMapper.selectList(any())).thenReturn(List.of(split()));
        var page = noConfig.listLedger(1L, "2026-08-01", "2026-08-31", null, null, null, 0, 100);
        long fee = page.items().stream()
                .filter(r -> "CHANNEL_FEE".equals(r.financialType()))
                .mapToLong(r -> r.amountCents())
                .findFirst().orElse(-1L);
        assertEquals(60L, fee);
    }

    @Test
    @DisplayName("通道费为负的直方防护：gross<=0 或 bps<=0 时不得返回负数")
    void noNegativeFee_edgeCases() {
        // 直接验证折算逻辑的边界：负数 gross 会让 channelFee 变成负数（平台收入倒挂）
        java.lang.reflect.Method m;
        try {
            m = FundBillService.class.getDeclaredMethod("estimateChannelFeeCents", long.class, int.class);
            m.setAccessible(true);
            assertEquals(0L, m.invoke(service, -100L, 60));
            assertEquals(0L, m.invoke(service, 0L, 60));
            assertEquals(0L, m.invoke(service, 10_000L, 0));
            assertTrue((long) m.invoke(service, 10_000L, 60) > 0);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("无法反射 estimateChannelFeeCents，方法可能被改名", e);
        }
    }

    @Test
    @DisplayName("CB-020③：日账单通道费实结优先——按 gross 占比分摊，尾差挂最大商户行")
    void dailyBill_actualFeeFromRecon_distributedByGrossShare() {
        // 日实结 90 分；M-1 gross 10000（25%）、M-2 gross 30000（75%）
        // 比例分摊：23 + 68 = 91 ≠ 90 → 尾差 −1 归当日最大 gross（M-2）⇒ 23 + 67 = 90（Σ 对平）
        PaymentReconciliation recon = new PaymentReconciliation();
        recon.setReconDate(java.time.LocalDate.of(2026, 8, 15));
        recon.setChannel("WECHAT");
        recon.setChannelFeeCents(90L);
        when(reconMapper.findByReconDateBetweenOrderByReconDateDesc(any(), any()))
                .thenReturn(List.of(recon));
        OrderRevenueSplit m1 = split();
        OrderRevenueSplit m2 = split();
        m2.setMerchantId("M-2");
        m2.setGrossCents(30_000L);
        when(splitMapper.selectList(any())).thenReturn(List.of(m1, m2));
        Merchant m2Merchant = new Merchant();
        m2Merchant.setMerchantId("M-2");
        m2Merchant.setMerchantName("商户二");
        when(merchantMapper.findAll()).thenReturn(List.of(
                merchant("M-1", "测试商户"), m2Merchant));

        List<FundDailyBillDto> rows = service.listDailyBills(1L, "2026-08-01", "2026-08-31");

        FundDailyBillDto rowM1 = rows.stream().filter(r -> "M-1".equals(r.merchantId()))
                .findFirst().orElseThrow();
        FundDailyBillDto rowM2 = rows.stream().filter(r -> "M-2".equals(r.merchantId()))
                .findFirst().orElseThrow();
        assertEquals(23L, rowM1.channelFeeCents());
        assertEquals(67L, rowM2.channelFeeCents());
        assertEquals(FundDailyBillDto.SOURCE_ACTUAL, rowM1.channelFeeSource());
        assertEquals(FundDailyBillDto.SOURCE_ACTUAL, rowM2.channelFeeSource());
        assertEquals(90L, rowM1.channelFeeCents() + rowM2.channelFeeCents());
    }

    @Test
    @DisplayName("CB-020③：当日无实结（未对账/历史行 channelFeeCents=null）⇒ 回落 bps 估算并标 ESTIMATED")
    void dailyBill_noActualFee_fallsBackToEstimate() {
        // mock 未 stub 时 getInt 返回 int 默认 0（0bps ⇒ 估算 0），显式 stub 60 才是「默认费率」语义
        when(systemConfigService.getInt("fund.channel_fee_bps", 60)).thenReturn(60);
        PaymentReconciliation historical = new PaymentReconciliation();
        historical.setReconDate(java.time.LocalDate.of(2026, 8, 15));
        historical.setChannelFeeCents(null); // V331 之前的历史行：无实结数据 ≠ 实结 0
        when(reconMapper.findByReconDateBetweenOrderByReconDateDesc(any(), any()))
                .thenReturn(List.of(historical));
        when(splitMapper.selectList(any())).thenReturn(List.of(split()));

        List<FundDailyBillDto> rows = service.listDailyBills(1L, "2026-08-01", "2026-08-31");

        assertEquals(1, rows.size());
        assertEquals(60L, rows.get(0).channelFeeCents());
        assertEquals(FundDailyBillDto.SOURCE_ESTIMATED, rows.get(0).channelFeeSource());
    }

    private static Merchant merchant(String id, String name) {
        Merchant m = new Merchant();
        m.setMerchantId(id);
        m.setMerchantName(name);
        return m;
    }
}