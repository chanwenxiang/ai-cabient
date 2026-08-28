package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpdateMerchantSkuPriceRequest;
import com.aicabinet.trade.mapper.*;
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
class MerchantSkuPricingConcurrencyTest {

    @Mock private DeviceSkuPriceMapper priceRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private PermissionService permissionService;
    @Mock private MerchantPortalGuard merchantPortalGuard;
    @Mock private AdminAuditService auditService;
    @Mock private AdminAuditLogMapper auditLogRepository;
    @Mock private MerchantSelfServiceGate merchantSelfServiceGate;
    @Mock private MerchantFeaturePackService merchantFeaturePackService;
    @Mock private InventoryLotService inventoryLotService;
    @Mock private DistributedLockService distributedLockService;

    private MerchantSkuPricingService service;

    @BeforeEach
    void setUp() {
        service = new MerchantSkuPricingService(priceRepository, inventoryRepository, skuCatalogRepository,
                deviceRepository, permissionService, merchantPortalGuard, auditService, auditLogRepository,
                merchantSelfServiceGate, merchantFeaturePackService, inventoryLotService, distributedLockService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
    }

    @Test
    void updatePricing_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                MerchantSkuPricingService.skuPriceLockKey("CAB-1", "SKU-A"), 60L, 5L))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.updatePricing(1L, "SKU-A",
                        new UpdateMerchantSkuPriceRequest("CAB-1", 500)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void updatePricing_whenDeviceMissing_unlocksLock() {
        when(distributedLockService.tryLock(
                MerchantSkuPricingService.skuPriceLockKey("CAB-2", "SKU-B"), 60L, 5L))
                .thenReturn(true);
        when(skuCatalogRepository.findById("SKU-B")).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class,
                () -> service.updatePricing(1L, "SKU-B",
                        new UpdateMerchantSkuPriceRequest("CAB-2", 500)));

        verify(distributedLockService).unlock(MerchantSkuPricingService.skuPriceLockKey("CAB-2", "SKU-B"));
    }
}
