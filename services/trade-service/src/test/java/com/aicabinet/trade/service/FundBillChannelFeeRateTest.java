package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.FinanceMarginDailyLockMapper;
import com.aicabinet.trade.mapper.InventoryWriteOffMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
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

    private FundBillService service;

    @BeforeEach
    void setUp() {
        service = new FundBillService(splitMapper, deviceInfoMapper, merchantMapper,
                marginLockMapper, orderMapper, lineMapper, writeOffMapper,
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
                marginLockMapper, orderMapper, lineMapper, writeOffMapper,
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
}