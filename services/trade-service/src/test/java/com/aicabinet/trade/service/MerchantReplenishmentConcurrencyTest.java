package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.MerchantReplenishmentRequestMapper;
import com.aicabinet.trade.support.MerchantPortalGuard;
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
class MerchantReplenishmentConcurrencyTest {

    @Mock private PermissionService permissionService;
    @Mock private MerchantPortalGuard merchantPortalGuard;
    @Mock private MerchantReplenishmentRequestMapper requestRepository;
    @Mock private DistributedLockService distributedLockService;

    private MerchantReplenishmentService service;

    @BeforeEach
    void setUp() {
        service = new MerchantReplenishmentService(
                permissionService, null, merchantPortalGuard, null, null, null, null,
                null, null, null, null, requestRepository, null, null, null,
                null, distributedLockService, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void acceptRequest_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MerchantReplenishmentService.replenishmentRequestLockKey(5L), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.acceptRequest(1L, 5L));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void submitRequest_whenDeviceLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MerchantReplenishmentService.replenishmentDeviceLockKey("CAB-001"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.submitRequest(1L, new com.aicabinet.common.dto.CreateMerchantReplenishmentRequest(
                        "CAB-001",
                        null,
                        java.util.List.of(new com.aicabinet.common.dto.CreateMerchantReplenishmentRequest.Line(
                                "SKU-1", 1)),
                        null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void rejectRequest_whenRequestNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                MerchantReplenishmentService.replenishmentRequestLockKey(5L), 60L, 5L))
                .thenReturn(true);
        when(requestRepository.findByIdForUpdate(5L)).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.rejectRequest(1L, 5L, null));

        verify(distributedLockService).unlock(MerchantReplenishmentService.replenishmentRequestLockKey(5L));
    }
}
