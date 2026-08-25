package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OrderRevenueSplit;
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

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RevenueSplitServiceTest {

    @Mock private OrderRevenueSplitMapper splitRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private MerchantMapper merchantRepository;
    @Mock private WeChatProfitSharingService profitSharingService;
    @Mock private MerchantWalletService merchantWalletService;
    @Mock private ProfitSharingReturnCompensationService returnCompensationService;
    @Mock private ProfitSharingReturnAlertService profitSharingAlertService;
    @Mock private DistributedLockService distributedLockService;

    private RevenueSplitService service;

    @BeforeEach
    void setUp() {
        service = new RevenueSplitService(splitRepository, deviceRepository, merchantRepository,
                profitSharingService, merchantWalletService, returnCompensationService,
                profitSharingAlertService, distributedLockService);
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
        when(merchantWalletService.debitIfAbsent(eq("M-1"), eq(540L), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(true);

        service.resyncSplitForOrder(order);

        verify(merchantWalletService).debitIfAbsent(
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

    @Test
    void confirmLedgerOnly_acquiresLockAndCreditsWallet() {
        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-L");
        split.setOrderId("O-L");
        split.setMerchantId("M-1");
        split.setStatus("LEDGER_ONLY");
        split.setMerchantCents(540L);

        when(splitRepository.findByOrderIdForUpdate("O-L")).thenReturn(Optional.of(split));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        OrderRevenueSplit result = service.confirmLedgerOnly(split);

        assertEquals("SETTLED", result.getStatus());
        verify(merchantWalletService).creditIfAbsent(
                eq("M-1"), eq(540L), eq("SPLIT_CREDIT"), eq("SPLIT"), eq("SPLIT-L"), anyString());
        verify(distributedLockService).unlock(RevenueSplitService.orderSplitLockKey("O-L"));
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
        when(profitSharingService.returnMerchantShare(any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(ReturnSubmitOutcome.SUCCESS);
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSplitAfterPartialRefund(order, false);

        verify(profitSharingService).returnMerchantShare(
                eq(split), eq(merchant), eq(540L), eq("PSR" + split.getSplitId() + ":g360"), anyString());
        verify(splitRepository).save(argThat(s ->
                s.getMerchantCents() == 360L
                        && s.getFailureReason() == null));
    }

    @Test
    void adjustSplitAfterPartialRefund_failedReturn_schedulesCompensation() {
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
        when(profitSharingService.returnMerchantShare(any(), any(), anyLong(), anyString(), anyString()))
                .thenReturn(ReturnSubmitOutcome.FAILED);
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.adjustSplitAfterPartialRefund(order, false);

        verify(profitSharingAlertService).sendReturnSubmitFailed(eq(split), anyString(), eq(540L), anyString());
        verify(returnCompensationService).scheduleReturnRetry(eq(split), eq(60));
        verify(splitRepository).save(argThat(s ->
                s.getFailureReason() != null && s.getWechatPendingReturnNo() != null));
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
}
