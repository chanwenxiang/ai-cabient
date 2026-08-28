package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.OrderRevenueSplit;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OrderRevenueSplitMapper;
import com.aicabinet.trade.payment.WeChatProfitSharingService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RevenueSplitConcurrencyTest {

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
    }

    @Test
    void resyncSplitForOrder_whenLockBusy_rejectsWithConflict() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-LOCK");
        when(distributedLockService.tryLock(
                RevenueSplitService.orderSplitLockKey("O-LOCK"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.resyncSplitForOrder(order));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void resyncSplitForOrder_acquiresLockAndRowLock() {
        CabinetOrder order = new CabinetOrder();
        order.setOrderId("O-OK");
        order.setTotalAmountCents(500);

        OrderRevenueSplit split = new OrderRevenueSplit();
        split.setSplitId("SPLIT-OK");
        split.setOrderId("O-OK");
        split.setMerchantId("M-1");
        split.setStatus("ACCRUED");
        split.setMerchantCents(400);

        when(distributedLockService.tryLock(
                RevenueSplitService.orderSplitLockKey("O-OK"), 60L, 5L))
                .thenReturn(true);
        when(splitRepository.findByOrderIdForUpdate("O-OK")).thenReturn(Optional.of(split));
        com.aicabinet.trade.domain.Merchant merchant = new com.aicabinet.trade.domain.Merchant();
        merchant.setMerchantId("M-1");
        merchant.setPlatformRateBps(1000);
        when(merchantRepository.findById("M-1")).thenReturn(Optional.of(merchant));
        when(splitRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.resyncSplitForOrder(order);

        verify(splitRepository).findByOrderIdForUpdate("O-OK");
        verify(distributedLockService).unlock(RevenueSplitService.orderSplitLockKey("O-OK"));
    }
}
