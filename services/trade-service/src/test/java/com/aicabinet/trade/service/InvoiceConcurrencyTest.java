package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateInvoiceRequest;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.InvoiceRequestMapper;
import com.aicabinet.trade.mapper.MerchantTaxProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceConcurrencyTest {

    @Mock private InvoiceRequestMapper invoiceRepository;
    @Mock private MerchantTaxProfileMapper taxProfileRepository;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private PermissionService permissionService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private com.aicabinet.trade.support.MerchantPortalGuard merchantPortalGuard;

    private InvoiceService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceService(invoiceRepository, taxProfileRepository, orderRepository,
                deviceRepository, permissionService, merchantScopeService, distributedLockService,
                merchantPortalGuard,
                org.mockito.Mockito.mock(com.aicabinet.trade.service.AdminAuditService.class));
    }

    @Test
    void applyByConsumer_whenOrderLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                InvoiceService.orderInvoiceLockKey("O-1"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.applyByConsumer(10001L, "O-1", new CreateInvoiceRequest("抬头", null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void issue_whenRequestLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                InvoiceService.invoiceRequestLockKey(9L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.issue(1L, 9L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void issue_whenRequestNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                InvoiceService.invoiceRequestLockKey(10L), 60L, 5L))
                .thenReturn(true);
        when(invoiceRepository.findByIdForUpdate(10L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.issue(1L, 10L));

        verify(distributedLockService).unlock(InvoiceService.invoiceRequestLockKey(10L));
    }

    // ── MA-154：消费者开票的归属闸（此前只钉过「锁忙」与「请求不存在」）─────────

    @Test
    void applyByConsumer_foreignUser_forbiddenAndNothingWritten() {
        when(distributedLockService.tryLock(
                InvoiceService.orderInvoiceLockKey("O-OWN"), 60L, 5L)).thenReturn(true);
        com.aicabinet.trade.domain.CabinetOrder other = new com.aicabinet.trade.domain.CabinetOrder();
        other.setOrderId("O-OWN");
        other.setUserId(20002L);
        other.setStatus("COMPLETED");
        when(orderRepository.findById("O-OWN")).thenReturn(java.util.Optional.of(other));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.applyByConsumer(10001L, "O-OWN", new CreateInvoiceRequest("抬头", null, null)));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode(), "他人订单必须停在归属门");
        org.mockito.Mockito.verifyNoInteractions(invoiceRepository);
    }

    @Test
    void applyByConsumer_owner_passesOwnershipGate() {
        // 正向对照：本人过得了归属闸——用「状态不可开票」的 409 证明失败原因不是归属，
        // 否则「闸写反成只放行非本人」也能让上一条保持绿
        when(distributedLockService.tryLock(
                InvoiceService.orderInvoiceLockKey("O-MINE"), 60L, 5L)).thenReturn(true);
        com.aicabinet.trade.domain.CabinetOrder mine = new com.aicabinet.trade.domain.CabinetOrder();
        mine.setOrderId("O-MINE");
        mine.setUserId(10001L);
        mine.setStatus("CANCELLED");
        when(orderRepository.findById("O-MINE")).thenReturn(java.util.Optional.of(mine));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.applyByConsumer(10001L, "O-MINE", new CreateInvoiceRequest("抬头", null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode(), "应停在状态门而不是归属门");
    }
}
