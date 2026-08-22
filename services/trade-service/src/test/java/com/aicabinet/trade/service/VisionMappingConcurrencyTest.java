package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertYoloMappingRequest;
import com.aicabinet.trade.mapper.AliyunCategoryMappingMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuVisionMappingMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VisionMappingConcurrencyTest {

    @Mock private SkuVisionMappingMapper yoloRepository;
    @Mock private AliyunCategoryMappingMapper aliyunRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private PermissionService permissionService;
    @Mock private DistributedLockService distributedLockService;

    private VisionMappingService service;

    @BeforeEach
    void setUp() {
        doNothing().when(permissionService).requirePermission(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
        service = new VisionMappingService(
                yoloRepository, aliyunRepository, skuCatalogRepository,
                permissionService, null, distributedLockService);
    }

    @Test
    void upsertYolo_whenLockBusy_rejectsWithConflict() {
        when(distributedLockService.tryLock(
                eq(VisionMappingService.yoloMappingLockKey("cola")), eq(60L), eq(5L)))
                .thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertYolo(1L, new UpsertYoloMappingRequest("cola", "SKU-1", 0.5f, "YOLO_COCO")));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void deleteAliyun_whenNotFound_unlocksLock() {
        when(distributedLockService.tryLock(
                eq(VisionMappingService.aliyunMappingLockKey("cat-1")), eq(60L), eq(5L)))
                .thenReturn(true);
        when(aliyunRepository.findByIdForUpdate("cat-1")).thenReturn(java.util.Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.deleteAliyun(1L, "cat-1"));

        verify(distributedLockService).unlock(VisionMappingService.aliyunMappingLockKey("cat-1"));
    }
}
