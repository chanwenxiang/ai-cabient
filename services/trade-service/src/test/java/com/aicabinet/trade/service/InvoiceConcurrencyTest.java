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

    private InvoiceService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceService(invoiceRepository, taxProfileRepository, orderRepository,
                deviceRepository, permissionService, merchantScopeService, distributedLockService);
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
}
