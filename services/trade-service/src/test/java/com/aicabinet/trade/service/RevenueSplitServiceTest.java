package com.aicabinet.trade.service;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
import com.aicabinet.trade.payment.WeChatProfitSharingService.ReturnSubmitOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RevenueSplitServiceTest {
    static {
        // M01 门禁修复引入 LambdaUpdateWrapper：纯 Mockito 环境需手动注册 TableInfo 缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                OrderRevenueSplit.class);
    }


    @Mock private OrderRevenueSplitMapper splitRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private MerchantMapper merchantRepository;
    @Mock private WeChatProfitSharingService profitSharingService;
    @Mock private MerchantWalletService merchantWalletService;
    @Mock private ProfitSharingReturnCompensationService returnCompensationService;
    @Mock private ProfitSharingReturnAlertService profitSharingAlertService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private ProfitSharingReturnLegService returnLegService;
    @Mock private CabinetOrderMapper orderRepository;

    private RevenueSplitService service;

    @BeforeEach
    void setUp() {
        service = new RevenueSplitService(splitRepository, deviceRepository, merchantRepository,
                profitSharingService, merchantWalletService, returnCompensationService,
                profitSharingAlertService, distributedLockService, returnLegService, orderRepository);
        org.mockito.Mockito.lenient().when(returnLegService.openLegs(anyString()))
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.lenient().when(distributedLockService.tryLock(anyString(), eq(60L), eq(5L)))
                .thenReturn(true);
    }

    @Test
    void resyncSplitForOrder_shouldClawbackPartialWalletCredit() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-1");
        split.setOrderId("O-1");
        split.setMerchantId("M-1");
        split.setStatus("LEDGER_ONLY");
        split.setGrossCents(1000);
        split.setMerchantCents(900);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-1");
        order.setTotalAmountCents(400);

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setPlatformRateBps(1000);

        when(splitRepository.findByOrderIdForUpdate("O-1")).thenReturn(Optional.of(split));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 审计 T4（2026-10-15）：冲正改 AllowNegative（对账动作可挂负，不受余额闸 412 拦截）
        when(merchantWalletService.debitIfAbsentAllowNegative(eq("M-1"), eq(540L), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        service.resyncSplitForOrder(order);

        verify(merchantWalletService).debitIfAbsentAllowNegative(
                eq("M-1"), eq(540L), eq("SPLIT_PARTIAL_REVERSE"),
                eq("SPLIT_PARTIAL_REV"), eq("SPLIT-1:g360"), contains("O-1"));
        verify(splitRepository).save(argThat(s -> s.getMerchantCents() == 360L && s.getGrossCents() == 400L));
    }

    @Test
    void resyncSplitForOrder_shouldUpdateAccruedWithoutWalletDebit() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-2");
        split.setOrderId("O-2");
        split.setMerchantId("M-1");
        split.setStatus("ACCRUED");
        split.setGrossCents(1000);
        split.setMerchantCents(900);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-2");
        order.setTotalAmountCents(400);

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setPlatformRateBps(1000);

        when(splitRepository.findByOrderIdForUpdate("O-2")).thenReturn(Optional.of(split));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resyncSplitForOrder(order);

        verify(merchantWalletService, never()).debitIfAbsent(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(splitRepository).save(argThat(s -> "ACCRUED".equals(s.getStatus()) && s.getMerchantCents() == 360L));
    }

    @Test
    void recordSplit_wechatPaid_shouldAutoSubmitAccruedSplit() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-NEW");
        order.setDeviceId("CAB-1");
        order.setPayTradeNo("WX-TXN-NEW");
        order.setTotalAmountCents(1000);

        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        device.setMerchantId("M-1");

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setStatus("ACTIVE");
        merchant.setPlatformRateBps(1000);
        merchant.setWechatReceiverId("1900000109");

        when(splitRepository.findByOrderIdForUpdate("O-NEW")).thenReturn(Optional.empty());
        when(deviceRepository.findById("CAB-1")).thenReturn(Optional.of(device));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(profitSharingService.isApiReady()).thenReturn(true);
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(profitSharingService.submitSplit(any(), eq(merchant), eq("WX-TXN-NEW")))
                .thenAnswer(inv -> inv.getArgument(0));

        service.recordSplit(order);

        verify(profitSharingService).submitSplit(any(OrderRevenueSplit.class), eq(merchant), eq("WX-TXN-NEW"));
    }

    @Test
    void recordSplit_zeroGross_skipsInsert() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-ZERO");
        order.setDeviceId("CAB-1");
        order.setTotalAmountCents(0);

        assertEquals(Optional.empty(), service.recordSplit(order));
        verify(splitRepository, never()).findByOrderIdForUpdate(any());
        verify(splitRepository, never()).save(any());
    }

    @Test
    void recordSplit_alipayPaid_shouldAutoSubmitAccruedSplit() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-ALI");
        order.setDeviceId("CAB-1");
        order.setPayChannel("ALIPAY");
        order.setPayTradeNo("ALI-TXN-1");
        order.setTotalAmountCents(800);

        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        device.setMerchantId("M-1");

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setStatus("ACTIVE");
        merchant.setPlatformRateBps(1000);
        merchant.setWechatReceiverId("1900000109");

        when(splitRepository.findByOrderIdForUpdate("O-ALI")).thenReturn(Optional.empty());
        when(deviceRepository.findById("CAB-1")).thenReturn(Optional.of(device));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(profitSharingService.isApiReady()).thenReturn(true);
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(profitSharingService.submitSplit(any(), eq(merchant), eq("ALI-TXN-1")))
                .thenAnswer(inv -> inv.getArgument(0));

        service.recordSplit(order);

        verify(profitSharingService).submitSplit(any(OrderRevenueSplit.class), eq(merchant), eq("ALI-TXN-1"));
    }

    @Test
    void recordSplit_duplicateKey_returnsExistingRow() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-DUP");
        order.setDeviceId("CAB-1");
        order.setTotalAmountCents(600);

        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        device.setMerchantId("M-1");

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setStatus("ACTIVE");
        merchant.setPlatformRateBps(1000);

        OrderRevenueSplit existing = new OrderRevenueSplit();
        existing.setSplitId("SPLIT-EXIST");
        existing.setOrderId("O-DUP");
        existing.setMerchantId("M-1");
        existing.setStatus("ACCRUED");
        existing.setMerchantCents(540);

        when(splitRepository.findByOrderIdForUpdate("O-DUP"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(existing));
        when(deviceRepository.findById("CAB-1")).thenReturn(Optional.of(device));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("uk_order_revenue_split_order_id"));

        Optional<OrderRevenueSplit> result = service.recordSplit(order);

        assertEquals("SPLIT-EXIST", result.map(OrderRevenueSplit::getSplitId).orElse(null));
        verify(profitSharingService, never()).submitSplit(any(), any(), anyString());
    }

    // ── 平台抽成比例（platformRateBps）的金额正确性：此前全仓测试无一处用非零 bps ──────────
    // 公式：gross = netCents（冻结毛额 − refunded）；platform = floor(gross * bps / 10000)；
    //       merchant = gross - platform（整数除法产生的零头归商户，保证两段之和恒等于 gross）。

    private CabinetOrder splitOrder(String orderId, int totalCents, int paidCents, int refundedCents) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId(orderId);
        order.setDeviceId("CAB-1");
        order.setTotalAmountCents(totalCents);
        order.setPaidAmountCents(paidCents);
        order.setRefundedCents(refundedCents);
        return order;
    }

    private void stubSplitPath(String orderId, int platformRateBps) {
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        device.setMerchantId("M-1");
        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setStatus("ACTIVE");
        merchant.setPlatformRateBps(platformRateBps);
        // 无 wechatReceiverId ⇒ 即使 platform>0 也走 LEDGER_ONLY（仅记账），不触达微信分账
        when(splitRepository.findByOrderIdForUpdate(orderId)).thenReturn(Optional.empty());
        when(deviceRepository.findById("CAB-1")).thenReturn(Optional.of(device));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void recordSplit_platformRate500_appliesFivePercent_roundedDown() {
        stubSplitPath("O-R500", 500);
        OrderRevenueSplit split = service.recordSplit(splitOrder("O-R500", 1000, 1000, 0)).orElseThrow();

        assertEquals(1000L, split.getGrossCents(), "毛额应取冻结实付");
        assertEquals(50L, split.getPlatformCents(), "1000 × 5% 应抽 50 分");
        assertEquals(950L, split.getMerchantCents(), "商户应得 950 分");
        assertEquals(1000L, split.getPlatformCents() + split.getMerchantCents(),
                "平台+商户必须恒等于毛额（不允许丢分或多分）");
        assertEquals("LEDGER_ONLY", split.getStatus(), "无微信接收方 ⇒ 仅记账");
    }

    @Test
    void recordSplit_oddAmount_floorsPlatform_zeroRemainderGoesToMerchant() {
        stubSplitPath("O-ODD", 500);
        // 333 × 5% = 16.65 ⇒ 向下取整 16，零头 0.65 归商户（317）
        OrderRevenueSplit split = service.recordSplit(splitOrder("O-ODD", 333, 333, 0)).orElseThrow();

        assertEquals(16L, split.getPlatformCents());
        assertEquals(317L, split.getMerchantCents());
        assertEquals(333L, split.getPlatformCents() + split.getMerchantCents());
    }

    @Test
    void recordSplit_partialRefund_usesNetBase_notTotal() {
        stubSplitPath("O-REF", 500);
        // total/paid 1000，已退 400 ⇒ 分账基数 600（AU-182 净额口径）⇒ 平台 30 / 商户 570
        OrderRevenueSplit split = service.recordSplit(splitOrder("O-REF", 1000, 1000, 400)).orElseThrow();

        assertEquals(600L, split.getGrossCents(), "部分退款后应按净额 600 分账，而不是 1000");
        assertEquals(30L, split.getPlatformCents());
        assertEquals(570L, split.getMerchantCents());
    }

    @Test
    void recordSplit_zeroRate_merchantGetsWholeGross() {
        stubSplitPath("O-R0", 0);
        OrderRevenueSplit split = service.recordSplit(splitOrder("O-R0", 875, 0, 0)).orElseThrow();

        assertEquals(0L, split.getPlatformCents(), "费率为 0（V382 默认）时平台不抽成");
        assertEquals(875L, split.getMerchantCents(), "商户应得全额 875 分");
        assertEquals("LEDGER_ONLY", split.getStatus());
    }

    /**
     * R20-2：支付分免密腿在结算时只是「完结订单」，微信**异步**扣款、钱未实收（订单 PENDING）。
     * 该单的分账行必须建，但**不得**把商户份额入钱包。
     */
    @Test
    void recordSplit_uncollectedPayscorePending_createsSplitButNeverCreditsWallet() {
        stubSplitPath("O-PS1", 0);
        CabinetOrder order = splitOrder("O-PS1", 900, 0, 0);
        order.setStatus("PENDING");

        OrderRevenueSplit split = service.recordSplit(order).orElseThrow();

        assertEquals("LEDGER_ONLY", split.getStatus(), "分账行仍要建，否则实收后无行可入");
        assertEquals(900L, split.getMerchantCents());
        verify(merchantWalletService, never())
                .creditIfAbsent(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    /** R20-2：待支付单被取消同样不得入账。 */
    @Test
    void recordSplit_cancelledOrder_neverCreditsWallet() {
        stubSplitPath("O-CX", 0);
        CabinetOrder order = splitOrder("O-CX", 900, 0, 0);
        order.setStatus("CANCELLED");

        service.recordSplit(order);

        verify(merchantWalletService, never())
                .creditIfAbsent(anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * R20-2 自愈腿：未实收时只建行不入账；DONE 回调/补缴成功后重进 recordSplit，
     * 走「已存在」支补入账，且以 splitId 为幂等键 ⇒ 重复进来也只该入一次。
     */
    @Test
    void recordSplit_reentryAfterCollection_creditsExactlyOnce() {
        OrderRevenueSplit existing = new OrderRevenueSplit();
        existing.setSplitId("SPLIT-PS");
        existing.setOrderId("O-PS2");
        existing.setMerchantId("M-1");
        existing.setStatus("LEDGER_ONLY");
        existing.setMerchantCents(900L);
        when(splitRepository.findByOrderIdForUpdate("O-PS2")).thenReturn(Optional.of(existing));
        when(merchantWalletService.creditIfAbsent(
                eq("M-1"), eq(900L), eq("SPLIT_CREDIT"), eq("SPLIT"), eq("SPLIT-PS"), anyString()))
                .thenReturn(true);

        CabinetOrder pending = splitOrder("O-PS2", 900, 900, 0);
        pending.setStatus("PENDING");
        service.recordSplit(pending);
        verify(merchantWalletService, never()).creditIfAbsent(
                eq("M-1"), eq(900L), eq("SPLIT_CREDIT"), eq("SPLIT"), eq("SPLIT-PS"), anyString());

        CabinetOrder paid = splitOrder("O-PS2", 900, 900, 0);
        paid.setStatus("PAID");
        service.recordSplit(paid);
        service.recordSplit(paid);

        verify(merchantWalletService, times(2)).creditIfAbsent(
                eq("M-1"), eq(900L), eq("SPLIT_CREDIT"), eq("SPLIT"), eq("SPLIT-PS"), anyString());
    }

    /**
     * 回归护栏：同步渠道（余额/微信直付/支付宝）结算时订单就是 PAID，行为与修前逐字一致——
     * 这条是「门加错方向导致商户少收」的哨兵，不许被顺手改成 PENDING。
     */
    @Test
    void recordSplit_paidOrder_creditsWalletAsBefore() {
        stubSplitPath("O-OK", 0);
        when(merchantWalletService.creditIfAbsent(
                eq("M-1"), eq(700L), eq("SPLIT_CREDIT"), eq("SPLIT"), anyString(), anyString()))
                .thenReturn(true);
        CabinetOrder order = splitOrder("O-OK", 700, 700, 0);
        order.setStatus("PAID");

        service.recordSplit(order);

        verify(merchantWalletService).creditIfAbsent(
                eq("M-1"), eq(700L), eq("SPLIT_CREDIT"), eq("SPLIT"), anyString(), anyString());
    }

    @Test
    void confirmLedgerOnly_acquiresLockAndCreditsWallet() {
        OrderRevenueSplit split = ledgerSplit("SPLIT-L", "O-L");

        when(orderRepository.findById("O-L")).thenReturn(Optional.of(collectedOrder("O-L")));
        when(splitRepository.findByOrderIdForUpdate("O-L")).thenReturn(Optional.of(split));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrderRevenueSplit result = service.confirmLedgerOnly(split);

        assertEquals("SETTLED", result.getStatus());
        verify(merchantWalletService).creditIfAbsent(
                eq("M-1"), eq(540L), eq("SPLIT_CREDIT"), eq("SPLIT"), eq("SPLIT-L"), anyString());
        verify(distributedLockService).unlock(RevenueSplitService.orderSplitLockKey("O-L"));
    }

    /**
     * R20-2：运营「确认完结」是 LEDGER_ONLY 仅剩的手动放款把手。
     * 订单尚未实收（支付分腿 PENDING）时必须显式拒绝，而不是把钱先打进商户钱包。
     */
    @Test
    void confirmLedgerOnly_uncollectedOrder_isRejectedWithoutCredit() {
        OrderRevenueSplit split = ledgerSplit("SPLIT-P", "O-P");
        CabinetOrder pending = new CabinetOrder();
        pending.setOrderId("O-P");
        pending.setStatus("PENDING");

        when(orderRepository.findById("O-P")).thenReturn(Optional.of(pending));
        when(splitRepository.findByOrderIdForUpdate("O-P")).thenReturn(Optional.of(split));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.confirmLedgerOnly(split));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("尚未实收"), error.getReason());
        verify(merchantWalletService, never()).creditIfAbsent(
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(splitRepository, never()).save(any());
        // 拒绝也要放锁，否则后续合法确认会被自己的锁卡住
        verify(distributedLockService).unlock(RevenueSplitService.orderSplitLockKey("O-P"));
    }

    /** 订单行查不到 ⇒ 同样拒绝：这条通道的失败方向只能是「少放款」，不能是凭空造钱。 */
    @Test
    void confirmLedgerOnly_missingOrder_isRejectedWithoutCredit() {
        OrderRevenueSplit split = ledgerSplit("SPLIT-M", "O-M");

        when(orderRepository.findById("O-M")).thenReturn(Optional.empty());
        when(splitRepository.findByOrderIdForUpdate("O-M")).thenReturn(Optional.of(split));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.confirmLedgerOnly(split));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        assertTrue(error.getReason().contains("查不到"), error.getReason());
        verify(merchantWalletService, never()).creditIfAbsent(
                anyString(), anyLong(), anyString(), anyString(), anyString(), anyString());
        verify(splitRepository, never()).save(any());
    }

    private OrderRevenueSplit ledgerSplit(String splitId, String orderId) {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId(splitId);
        split.setOrderId(orderId);
        split.setMerchantId("M-1");
        split.setStatus("LEDGER_ONLY");
        split.setMerchantCents(540L);
        return split;
    }

    private CabinetOrder collectedOrder(String orderId) {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId(orderId);
        order.setStatus("PAID");
        return order;
    }

    @Test
    void resyncSplitForOrder_submittedWechatIncrease_shouldRecalcAndFlagManual() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-7");
        split.setOrderId("O-7");
        split.setMerchantId("M-1");
        split.setStatus("WECHAT_SUBMITTED");
        split.setMerchantCents(360);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-7");
        order.setTotalAmountCents(1000);

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setPlatformRateBps(1000);

        when(splitRepository.findByOrderIdForUpdate("O-7")).thenReturn(Optional.of(split));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resyncSplitForOrder(order);

        verify(profitSharingService, never()).submitSplit(any(), any(), anyString());
        verify(profitSharingAlertService).sendManualSupplementRequired(eq(split), eq(360L), eq(900L));
        verify(splitRepository).save(argThat(s ->
                s.getMerchantCents() == 900L
                        && s.getFailureReason() != null
                        && s.getFailureReason().contains("需人工补分账")));
    }

    @Test
    void resyncSplitForOrder_increaseAccrued_shouldAutoSubmitWeChatSplit() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-5");
        split.setOrderId("O-5");
        split.setMerchantId("M-1");
        split.setStatus("ACCRUED");
        split.setMerchantCents(360);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-5");
        order.setPayTradeNo("WX-TXN-5");
        order.setTotalAmountCents(1000);

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setPlatformRateBps(1000);
        merchant.setWechatReceiverId("1900000109");

        when(splitRepository.findByOrderIdForUpdate("O-5")).thenReturn(Optional.of(split));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(profitSharingService.isApiReady()).thenReturn(true);
        when(profitSharingService.submitSplit(any(), any(), anyString())).thenReturn(split);
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resyncSplitForOrder(order);

        verify(profitSharingService).submitSplit(eq(split), eq(merchant), eq("WX-TXN-5"));
        verify(splitRepository).save(argThat(s -> s.getMerchantCents() == 900L));
    }

    @Test
    void resyncSplitForOrder_increaseLedgerOnly_shouldCreditWalletDelta() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-6");
        split.setOrderId("O-6");
        split.setMerchantId("M-1");
        split.setStatus("LEDGER_ONLY");
        split.setMerchantCents(360);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-6");
        order.setTotalAmountCents(1000);

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setPlatformRateBps(1000);

        when(splitRepository.findByOrderIdForUpdate("O-6")).thenReturn(Optional.of(split));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(merchantWalletService.creditIfAbsent(eq("M-1"), eq(540L), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        service.resyncSplitForOrder(order);

        verify(merchantWalletService).creditIfAbsent(
                eq("M-1"), eq(540L), eq("SPLIT_PARTIAL_CREDIT"),
                eq("SPLIT_PARTIAL"), eq("SPLIT-6:g900"), contains("O-6"));
    }

    @Test
    void adjustSplitAfterPartialRefund_shouldFlagSubmittedWechatSplit() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-3");
        split.setOrderId("O-3");
        split.setMerchantId("M-1");
        split.setStatus("WECHAT_SUBMITTED");
        split.setGrossCents(1000);
        split.setMerchantCents(900);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-3");
        order.setTotalAmountCents(400);

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setPlatformRateBps(1000);

        when(splitRepository.findByOrderIdForUpdate("O-3")).thenReturn(Optional.of(split));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSplitAfterPartialRefund(order, false);

        // AU-141：finalize 事务内不再同步提交微信回退——落一条待确认腿 + 30s 补偿任务异步提交
        verify(profitSharingService, never()).returnMerchantShare(any(), any(), anyLong(), anyString(), anyString());
        // R20-9：不再断言 split 单值列被覆盖，改钉「按腿写入」的入参。
        verify(returnLegService).recordOutcome(eq(split), eq("PSR" + split.getSplitId() + ":g360"),
                eq(540L), eq(ReturnSubmitOutcome.PROCESSING), anyString());
        verify(splitRepository).save(argThat(s ->
                s.getMerchantCents() == 360L
                        && s.getFailureReason() == null));
        verify(returnCompensationService).scheduleReturnRetry(eq(split), anyList(), eq(30));
    }

    @Test
    void adjustSplitAfterPartialRefund_pendingSubmission_schedulesCompensationImmediately() {
        // AU-141：部分退路径不再同步提交渠道——无条件落 PENDING + 30s 补偿任务
        //（提交与查单确认由 CompensationTaskScheduler 的独立短事务完成）
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-F");
        split.setOrderId("O-F");
        split.setMerchantId("M-1");
        split.setStatus("WECHAT_SUBMITTED");
        split.setMerchantCents(900);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-F");
        order.setTotalAmountCents(400);

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setPlatformRateBps(1000);

        when(splitRepository.findByOrderIdForUpdate("O-F")).thenReturn(Optional.of(split));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSplitAfterPartialRefund(order, false);

        verify(profitSharingService, never()).returnMerchantShare(any(), any(), anyLong(), anyString(), anyString());
        verify(profitSharingAlertService, never()).sendReturnSubmitFailed(any(), anyString(), anyLong(), anyString());
        verify(returnLegService).recordOutcome(eq(split), eq("PSR" + split.getSplitId() + ":g360"),
                eq(540L), eq(ReturnSubmitOutcome.PROCESSING), anyString());
        verify(returnCompensationService).scheduleReturnRetry(eq(split), anyList(), eq(30));
    }

    @Test
    void adjustSplitAfterOrderChange_decrease_delegatesToPartialAdjust() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-4");
        split.setOrderId("O-4");
        split.setMerchantId("M-1");
        split.setStatus("ACCRUED");
        split.setMerchantCents(900);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-4");
        order.setTotalAmountCents(400);

        when(splitRepository.findByOrderIdForUpdate("O-4")).thenReturn(Optional.of(split));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(new Merchant()));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSplitAfterOrderChange(order, 800);

        verify(splitRepository).save(any(OrderRevenueSplit.class));
    }

    /** W1: 无微信接收方 → LEDGER_ONLY 立刻入账；重复 recordSplit 只入一次钱（靠同一 splitId 幂等键）。 */
    @Test
    void recordSplit_ledgerOnly_creditsWalletOnce_andIdempotentReplay() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-W1");
        order.setDeviceId("CAB-1");
        order.setTotalAmountCents(1000);

        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        device.setMerchantId("M-1");

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setStatus("ACTIVE");
        merchant.setPlatformRateBps(1000);
        merchant.setWechatReceiverId(null);

        when(splitRepository.findByOrderIdForUpdate("O-W1")).thenReturn(Optional.empty());
        when(deviceRepository.findById("CAB-1")).thenReturn(Optional.of(device));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(merchantWalletService.creditIfAbsent(eq("M-1"), eq(900L), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        Optional<OrderRevenueSplit> first = service.recordSplit(order);
        assertEquals("LEDGER_ONLY", first.map(OrderRevenueSplit::getStatus).orElse(null));
        assertEquals(900L, first.map(OrderRevenueSplit::getMerchantCents).orElse(-1L));
        verify(merchantWalletService).creditIfAbsent(
                eq("M-1"), eq(900L), eq("SPLIT_CREDIT"), eq("SPLIT"),
                eq(first.get().getSplitId()), contains("O-W1"));

        OrderRevenueSplit existing = first.get();
        when(splitRepository.findByOrderIdForUpdate("O-W1")).thenReturn(Optional.of(existing));
        Optional<OrderRevenueSplit> second = service.recordSplit(order);
        assertEquals(existing.getSplitId(), second.map(OrderRevenueSplit::getSplitId).orElse(null));
        // R20-2 后重放会**再次发起**入账尝试（这是实收自愈入口），但钱只动一次：判据不是
        // 「不发起」而是「发起的幂等键逐字相同」⇒ creditIfAbsent + V379 ref 部分唯一索引兜住
        // （WalletRefIdempotencySqlIT 在真库钉这条）。所以这里核两次调用的键一致。
        org.mockito.ArgumentCaptor<String> refCap = org.mockito.ArgumentCaptor.forClass(String.class);
        org.mockito.ArgumentCaptor<String> refTypeCap = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(merchantWalletService, times(2)).creditIfAbsent(
                anyString(), anyLong(), anyString(), refTypeCap.capture(), refCap.capture(), anyString());
        assertEquals(java.util.List.of("SPLIT", "SPLIT"), refTypeCap.getAllValues());
        assertEquals(java.util.List.of(existing.getSplitId(), existing.getSplitId()), refCap.getAllValues(),
                "重放必须打同一个 splitId，否则商户钱包会双记");
        verify(splitRepository, times(1)).save(any());
    }

    /** W2: 全额退冲正钱包；二次 void 不重复 reverse。 */
    @Test
    void voidSplitOnFullRefund_reversesLedgerCredit_andSecondCallIsNoop() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-W2");
        split.setOrderId("O-W2");
        split.setMerchantId("M-1");
        split.setStatus("LEDGER_ONLY");
        split.setMerchantCents(900L);

        when(splitRepository.findByOrderIdForUpdate("O-W2")).thenReturn(Optional.of(split));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(merchantWalletService.reverseCreditAllowNegativeIfPresent(eq("M-1"), eq(900L), anyString(), any()))
                .thenReturn(true);

        service.voidSplitOnFullRefund("O-W2");

        assertEquals("VOIDED", split.getStatus());
        verify(merchantWalletService).reverseCreditAllowNegativeIfPresent(
                eq("M-1"), eq(900L), eq("SPLIT_REVERSE"),
                argThat(cmd -> "SPLIT_REV".equals(cmd.reverseRefType())
                        && "SPLIT-W2".equals(cmd.reverseRefId())
                        && "SPLIT".equals(cmd.originalRefType())
                        && "SPLIT-W2".equals(cmd.originalRefId())));
        verify(splitRepository).save(argThat(s -> "VOIDED".equals(s.getStatus())));

        service.voidSplitOnFullRefund("O-W2");
        verify(merchantWalletService, times(1)).reverseCreditAllowNegativeIfPresent(anyString(), anyLong(), anyString(), any());
        verify(splitRepository, times(1)).save(any());
    }

    /**
     * 拍板 2026-10-17「平台分账先不收」：rate=0 时**不得**向微信发分账请求
     * （receiver.amount 会等于订单毛额，全额分账是否被服务商允许未实证），
     * 商户份额整笔走钱包入账（LEDGER_ONLY）。
     */
    @Test
    void recordSplit_platformRateZero_ledgerOnlyCreditsGrossAndNeverSubmitsSharing() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-RATE0");
        order.setDeviceId("CAB-1");
        order.setPayTradeNo("WX-TXN-RATE0");
        order.setTotalAmountCents(1000);

        DeviceInfo device = new DeviceInfo();
        device.setDeviceId("CAB-1");
        device.setMerchantId("M-1");

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setStatus("ACTIVE");
        merchant.setPlatformRateBps(0);
        merchant.setWechatReceiverId("1900000109");

        when(splitRepository.findByOrderIdForUpdate("O-RATE0")).thenReturn(Optional.empty());
        when(deviceRepository.findById("CAB-1")).thenReturn(Optional.of(device));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(merchantWalletService.creditIfAbsent(
                eq("M-1"), eq(1000L), eq("SPLIT_CREDIT"), eq("SPLIT"), anyString(), anyString()))
                .thenReturn(true);

        Optional<OrderRevenueSplit> result = service.recordSplit(order);

        assertEquals("LEDGER_ONLY", result.map(OrderRevenueSplit::getStatus).orElse(null));
        verify(splitRepository).save(argThat(s ->
                s.getPlatformCents() == 0L && s.getMerchantCents() == 1000L));
        verify(profitSharingService, never()).submitSplit(any(), any(), anyString());
    }

    /**
     * 改单重算时费率已被调到 0（V382 存量归 0 / 运营手工改）：
     * 未提交微信的 ACCRUED 行翻成 LEDGER_ONLY 并**按全额**入钱包
     * （ACCRUED 期间钱包从未入账，补差额会少付商户 oldMerchantCents）。
     */
    @Test
    void resyncSplitForOrder_platformRateZero_flipsAccruedToLedgerAndCreditsFullGross() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-R0");
        split.setOrderId("O-R0");
        split.setMerchantId("M-1");
        split.setStatus("ACCRUED");
        split.setGrossCents(1000);
        split.setPlatformCents(100);
        split.setMerchantCents(900);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-R0");
        order.setPayTradeNo("WX-TXN-R0");
        order.setTotalAmountCents(1000);

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setStatus("ACTIVE");
        merchant.setPlatformRateBps(0);
        merchant.setWechatReceiverId("1900000109");

        when(splitRepository.findByOrderIdForUpdate("O-R0")).thenReturn(Optional.of(split));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(merchantWalletService.creditIfAbsent(
                eq("M-1"), eq(1000L), eq("SPLIT_CREDIT"), eq("SPLIT"), eq("SPLIT-R0"), anyString()))
                .thenReturn(true);

        service.resyncSplitForOrder(order);

        verify(splitRepository).save(argThat(s -> "LEDGER_ONLY".equals(s.getStatus())
                && s.getPlatformCents() == 0L && s.getMerchantCents() == 1000L));
        // 入账是全额 1000（SPLIT_CREDIT），不是差额 100（SPLIT_PARTIAL_CREDIT）
        verify(merchantWalletService, never()).creditIfAbsent(
                anyString(), anyLong(), eq("SPLIT_PARTIAL_CREDIT"), anyString(), anyString(), anyString());
    }

    /**
     * AU-182（2026-10-19）：金额退路径——total 不被改写（refunded 累加）时，分账重算
     * 也必须冲减。旧代码按 total=1000 重算 ⇒ merchant 不变、returnCents=0（不冲减的缺陷）；
     * 同源净额口径 = 冻结毛额 1000 − refunded 700 = 300 ⇒ merchant 270、钱包冲回 630。
     */
    @Test
    void adjustSplitAfterPartialRefund_amountRefund_shouldClawbackByNet() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-AU182A");
        split.setOrderId("O-AU182A");
        split.setMerchantId("M-1");
        split.setStatus("LEDGER_ONLY");
        split.setGrossCents(1000);
        split.setMerchantCents(900);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-AU182A");
        order.setTotalAmountCents(1000);   // 金额退不改写 total
        order.setPaidAmountCents(1000);    // V384 冻结毛额
        order.setRefundedCents(700);       // 金额退累加

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setPlatformRateBps(1000);

        when(splitRepository.findByOrderIdForUpdate("O-AU182A")).thenReturn(Optional.of(split));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(merchantWalletService.debitIfAbsentAllowNegative(eq("M-1"), eq(630L), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        service.adjustSplitAfterPartialRefund(order, false);

        verify(merchantWalletService).debitIfAbsentAllowNegative(
                eq("M-1"), eq(630L), eq("SPLIT_PARTIAL_REVERSE"),
                eq("SPLIT_PARTIAL_REV"), eq("SPLIT-AU182A:g270"), contains("O-AU182A"));
        verify(splitRepository).save(argThat(s ->
                s.getGrossCents() == 300L && s.getMerchantCents() == 270L && s.getPlatformCents() == 30L));
    }

    /**
     * AU-182：按行部分退——total 已被改写为剩余应付（SettlementOrderSupport），
     * 分账基数必须取<b>冻结毛额−refunded</b>而不是 total（后者会把退款再减一遍）。
     * 冻结 1000、refunded 700 ⇒ 净额 300（不是 max(0, 300−700)=0，也不是 total=300 减两遍）。
     */
    @Test
    void resyncSplitForOrder_rowRefund_baseUsesFrozenGrossNotTotal() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-AU182B");
        split.setOrderId("O-AU182B");
        split.setMerchantId("M-1");
        split.setStatus("LEDGER_ONLY");
        split.setGrossCents(1000);
        split.setMerchantCents(900);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-AU182B");
        order.setTotalAmountCents(300);    // 按行退已改写为剩余应付
        order.setPaidAmountCents(1000);    // 冻结毛额不动
        order.setRefundedCents(700);

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setPlatformRateBps(1000);

        when(splitRepository.findByOrderIdForUpdate("O-AU182B")).thenReturn(Optional.of(split));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(merchantWalletService.debitIfAbsentAllowNegative(eq("M-1"), eq(630L), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        service.resyncSplitForOrder(order);

        verify(splitRepository).save(argThat(s ->
                s.getGrossCents() == 300L && s.getMerchantCents() == 270L));
    }

    /**
     * AU-182：改单增额——冻结值只增不减，补扣到账后 freezePaidAmount 会把冻结毛额抬到
     * 实收合计 ⇒ 分账基数随冻结值上升，而不是旧代码那样只能靠 total（欠款单/历史单 total
     * 与实收脱钩时两套口径漂移）。
     */
    @Test
    void resyncSplitForOrder_increase_baseFollowsFrozenGross() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-AU182C");
        split.setOrderId("O-AU182C");
        split.setMerchantId("M-1");
        split.setStatus("LEDGER_ONLY");
        split.setMerchantCents(900);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-AU182C");
        order.setTotalAmountCents(1500);
        order.setPaidAmountCents(1500);    // 补扣后冻结值已上升

        Merchant merchant = new Merchant();
        merchant.setMerchantId("M-1");
        merchant.setPlatformRateBps(1000);

        when(splitRepository.findByOrderIdForUpdate("O-AU182C")).thenReturn(Optional.of(split));
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(merchantWalletService.creditIfAbsent(eq("M-1"), eq(450L), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        service.resyncSplitForOrder(order);

        verify(merchantWalletService).creditIfAbsent(
                eq("M-1"), eq(450L), eq("SPLIT_PARTIAL_CREDIT"),
                eq("SPLIT_PARTIAL"), eq("SPLIT-AU182C:g1350"), contains("O-AU182C"));
        verify(splitRepository).save(argThat(s -> s.getGrossCents() == 1500L && s.getMerchantCents() == 1350L));
    }
}
