package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkuVisionEnrollmentConcurrencyTest {

    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private PermissionService permissionService;
    @Mock private DistributedLockService distributedLockService;

    private SkuVisionEnrollmentService service;

    @BeforeEach
    void setUp() {
        doNothing().when(permissionService).requirePermission(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
        service = new SkuVisionEnrollmentService(
                skuCatalogRepository,
                null,
                null,
                permissionService,
                null,
                null,
                null,
                null,
                null,
                null,
                distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void advanceEnrollment_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                SkuVisionEnrollmentService.skuVisionLockKey("SKU-A"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.advanceEnrollment(1L, "SKU-A"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void advanceEnrollment_whenNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                SkuVisionEnrollmentService.skuVisionLockKey("SKU-MISS"), 60L, 5L))
                .thenReturn(true);
        when(skuCatalogRepository.findByIdForUpdate("SKU-MISS")).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.advanceEnrollment(1L, "SKU-MISS"));

        verify(distributedLockService).unlock(SkuVisionEnrollmentService.skuVisionLockKey("SKU-MISS"));
    }
}
