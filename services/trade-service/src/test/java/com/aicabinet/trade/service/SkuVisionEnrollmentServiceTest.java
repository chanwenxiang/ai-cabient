package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertSkuRequest;
import com.aicabinet.common.dto.UpsertSkuVisionEnrollmentRequest;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.StagingProperties;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.SkuVisionMapping;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuVisionMappingMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SkuVisionEnrollmentServiceTest {

    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private SkuVisionMappingMapper yoloRepository;
    @Mock private DeviceSlotService deviceSlotService;
    @Mock private PermissionService permissionService;
    @Mock private AdminAuditService auditService;
    @Mock private VisionServiceClient visionServiceClient;
    @Mock private UserInfoMapper userInfoRepository;
    @Mock private FileAttachmentService fileAttachmentService;
    @Mock private DistributedLockService distributedLockService;

    private SkuVisionEnrollmentService service;

    @BeforeEach
    void setUp() {
        when(distributedLockService.tryLock(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(true);
        service = new SkuVisionEnrollmentService(
                skuCatalogRepository,
                yoloRepository,
                deviceSlotService,
                permissionService,
                auditService,
                new StagingProperties(false, false),
                new ObjectMapper(),
                visionServiceClient,
                userInfoRepository,
                fileAttachmentService,
                distributedLockService);
    }

    @Test
    void enrollSku_shouldPersistCatalogAndMapping() {
        when(skuCatalogRepository.findByIdForUpdate("SKU-NEW-001")).thenReturn(Optional.empty());
        when(yoloRepository.findByIdForUpdate("cola_demo")).thenReturn(Optional.empty());
        when(skuCatalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(yoloRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var req = new UpsertSkuVisionEnrollmentRequest(
                new UpsertSkuRequest(
                        "SKU-NEW-001", "可乐演示", 350, null, true, null, null, null, null, "ACTIVE",
                        null, null, null, null, null, null, 0.92f, "cola_demo", "MAPPING", 0.5f, null,
                        null, null, null, null),
                "cola_demo", "MAPPING", 0.5f, null, "YOLO_SKU");

        when(skuCatalogRepository.nextSkuCode()).thenReturn(100001L);
        when(skuCatalogRepository.existsById("SKU-NEW-001")).thenReturn(false);
        when(skuCatalogRepository.existsByBarcode(null, "SKU-NEW-001")).thenReturn(false);

        var dto = service.enrollSku(1L, req);

        assertEquals("SKU-NEW-001", dto.skuId());
        assertEquals(100001L, dto.skuCode());
        assertEquals("MAPPING", dto.visionEnrollmentStatus());
        assertEquals("cola_demo", dto.yoloClassName());
        verify(permissionService).requirePermission(1L, "ops:sku:edit");
        verify(permissionService).requirePermission(1L, "ops:vision:edit");
        ArgumentCaptor<SkuVisionMapping> mapCap = ArgumentCaptor.forClass(SkuVisionMapping.class);
        verify(yoloRepository).save(mapCap.capture());
        assertEquals("cola_demo", mapCap.getValue().getClassName());
        assertEquals("SKU-NEW-001", mapCap.getValue().getSkuId());
    }

    @Test
    void advanceEnrollment_shouldMoveDraftToMapping() {
        SkuCatalog sku = baseSku("SKU-DRAFT", "DRAFT", "cola_demo");
        when(skuCatalogRepository.findByIdForUpdate("SKU-DRAFT")).thenReturn(Optional.of(sku));
        when(yoloRepository.existsById("cola_demo")).thenReturn(true);
        when(skuCatalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var row = service.advanceEnrollment(9L, "SKU-DRAFT");

        assertEquals("MAPPING", row.sku().visionEnrollmentStatus());
        assertEquals("TESTED", row.nextStatus());
        assertFalse(row.mappingEffective());
        assertEquals(SkuVisionEnrollmentService.MODEL_PIPELINE_WAITING, row.modelPipelineStatus());
    }

    @Test
    void advanceEnrollment_shouldMoveMappingToTested() {
        SkuCatalog sku = baseSku("SKU-A", "MAPPING", "cola_demo");
        when(skuCatalogRepository.findByIdForUpdate("SKU-A")).thenReturn(Optional.of(sku));
        when(yoloRepository.existsById("cola_demo")).thenReturn(true);
        when(skuCatalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var row = service.advanceEnrollment(9L, "SKU-A");

        assertEquals("TESTED", row.sku().visionEnrollmentStatus());
        assertEquals("PRODUCTION", row.nextStatus());
        assertEquals(SkuVisionEnrollmentService.MODEL_PIPELINE_WAITING, row.modelPipelineStatus());
        assertFalse(row.mappingEffective());
    }

    @Test
    void advanceEnrollment_toProduction_shouldMarkMappingEffective() {
        SkuCatalog sku = baseSku("SKU-B", "TESTED", "cola_demo");
        when(skuCatalogRepository.findByIdForUpdate("SKU-B")).thenReturn(Optional.of(sku));
        when(yoloRepository.existsById("cola_demo")).thenReturn(true);
        when(skuCatalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var row = service.advanceEnrollment(9L, "SKU-B");

        assertEquals("PRODUCTION", row.sku().visionEnrollmentStatus());
        assertTrue(row.mappingEffective());
        assertNull(row.nextStatus());
        assertEquals(SkuVisionEnrollmentService.MODEL_PIPELINE_WAITING, row.modelPipelineStatus());
    }

    @Test
    void advanceEnrollment_shouldRejectWhenAlreadyProduction() {
        SkuCatalog sku = baseSku("SKU-C", "PRODUCTION", "cola_demo");
        when(skuCatalogRepository.findByIdForUpdate("SKU-C")).thenReturn(Optional.of(sku));

        assertThrows(ResponseStatusException.class, () -> service.advanceEnrollment(1L, "SKU-C"));
    }

    @Test
    void updateEnrollmentStatus_shouldRequireExistingMapping() {
        SkuCatalog sku = baseSku("SKU-D", "DRAFT", "missing_class");
        when(skuCatalogRepository.findByIdForUpdate("SKU-D")).thenReturn(Optional.of(sku));
        when(yoloRepository.existsById("missing_class")).thenReturn(false);

        assertThrows(ResponseStatusException.class,
                () -> service.updateEnrollmentStatus(1L, "SKU-D", "MAPPING"));
    }

    @Test
    void pipelineMeta_shouldExposeWaitingRealModel() {
        var meta = service.pipelineMeta(1L);
        assertEquals(SkuVisionEnrollmentService.MODEL_PIPELINE_WAITING, meta.modelPipelineStatus());
        assertEquals(4, meta.steps().size());
        assertEquals(List.of("DRAFT", "MAPPING", "TESTED", "PRODUCTION"), meta.statusOrder());
    }

    @Test
    void listEnrollmentRows_shouldAnnotateProduction() {
        SkuCatalog prod = baseSku("SKU-P", "PRODUCTION", "cola_demo");
        SkuCatalog draft = baseSku("SKU-Q", "DRAFT", null);
        when(skuCatalogRepository.findAllByOrderBySkuCodeAsc()).thenReturn(List.of(prod, draft));

        var rows = service.listEnrollmentRows(1L);

        assertEquals(2, rows.size());
        assertTrue(rows.get(0).mappingEffective());
        assertFalse(rows.get(1).mappingEffective());
        assertEquals(SkuVisionEnrollmentService.MODEL_PIPELINE_WAITING, rows.get(0).modelPipelineStatus());
    }

    private static SkuCatalog baseSku(String id, String status, String className) {
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId(id);
        sku.setSkuName(id);
        sku.setPriceCents(300);
        sku.setStatus("ACTIVE");
        sku.setVisionEnabled(true);
        sku.setVisionEnrollmentStatus(status);
        sku.setYoloClassName(className);
        sku.setDetectionMinConfidence(0.5f);
        sku.setMinChargeConfidence(0.92f);
        return sku;
    }
}
