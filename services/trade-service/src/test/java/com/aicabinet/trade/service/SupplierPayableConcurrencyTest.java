package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.SupplierMapper;
import com.aicabinet.trade.mapper.SupplierPayableMapper;
import com.aicabinet.trade.mapper.SupplierPaymentMapper;
import com.aicabinet.trade.mapper.WarehouseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SupplierPayableConcurrencyTest {

    @Mock private PermissionService permissionService;
    @Mock private SupplierPayableMapper payableRepository;
    @Mock private SupplierPaymentMapper paymentRepository;
    @Mock private SupplierMapper supplierRepository;
    @Mock private WarehouseMapper warehouseRepository;
    @Mock private DistributedLockService distributedLockService;

    private SupplierPayableService service;

    @BeforeEach
    void setUp() {
        service = new SupplierPayableService(permissionService, payableRepository, paymentRepository,
                supplierRepository, warehouseRepository, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void pay_whenPayableLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                SupplierPayableService.payableLockKey(88L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.pay(1L, 88L, new com.aicabinet.common.dto.PaySupplierRequest(1000L, null, null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }
}
