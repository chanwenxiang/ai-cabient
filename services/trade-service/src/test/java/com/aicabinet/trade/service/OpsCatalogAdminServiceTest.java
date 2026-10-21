package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertSkuRequest;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.AliyunCategoryMappingMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OpsCatalogAdminServiceTest {

    @Mock PermissionService permissionService;
    @Mock SkuCatalogMapper skuCatalogRepository;
    @Mock AliyunCategoryMappingMapper aliyunCategoryMappingRepository;
    @Mock UserInfoMapper userInfoRepository;
    @Mock AdminAuditService auditService;
    @Mock FileAttachmentService fileAttachmentService;
    @Mock com.aicabinet.trade.mapper.SkuSceneLimitMapper skuSceneLimitRepository;

    private OpsCatalogAdminService service;

    @BeforeEach
    void setUp() {
        service = new OpsCatalogAdminService(
                permissionService, skuCatalogRepository, aliyunCategoryMappingRepository,
                userInfoRepository, auditService, fileAttachmentService,
                new SkuSceneLimitService(skuSceneLimitRepository, skuCatalogRepository));
    }

    @Test
    void trimToNull_blankBecomesNull() {
        assertNull(OpsCatalogAdminService.trimToNull(null));
        assertNull(OpsCatalogAdminService.trimToNull("  "));
        assertEquals("ab", OpsCatalogAdminService.trimToNull(" ab "));
    }

    @Test
    void applySkuRequest_defaultsUnitToPiece() {
        SkuCatalog sku = new SkuCatalog();
        OpsCatalogAdminService.applySkuRequest(sku, minimalRequest(null));
        assertEquals("件", sku.getUnit());
        assertEquals("可乐", sku.getSkuName());
        assertEquals(350, sku.getPriceCents());
    }

    @Test
    void createSku_duplicateId_conflicts() {
        when(skuCatalogRepository.nextSkuCode()).thenReturn(1001L);
        when(skuCatalogRepository.existsById("1001")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createSku(10001L, minimalRequest(null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(ApiMessages.SKU_EXISTS, ex.getReason());
        verify(skuCatalogRepository, never()).save(any());
    }

    @Test
    void createSku_rejectsNonNumericId() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createSku(10001L, minimalRequest("SKU-DUP")));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals(ApiMessages.SKU_ID_NUMERIC, ex.getReason());
        verify(skuCatalogRepository, never()).save(any());
    }

    @Test
    void createSku_duplicateNameAndSpec_conflicts() {
        when(skuCatalogRepository.nextSkuCode()).thenReturn(42L);
        when(skuCatalogRepository.existsById("42")).thenReturn(false);
        when(skuCatalogRepository.existsBySkuCode(42L, null)).thenReturn(false);
        when(skuCatalogRepository.existsByBarcode(null, null)).thenReturn(false);
        when(skuCatalogRepository.existsByNameAndSpec("可乐", null, null)).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createSku(10001L, minimalRequest(null)));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(ApiMessages.SKU_NAME_EXISTS, ex.getReason());
        verify(skuCatalogRepository, never()).save(any());
    }

    @Test
    void createSku_assignsNumericId() {
        when(skuCatalogRepository.nextSkuCode()).thenReturn(42L);
        when(skuCatalogRepository.existsById("42")).thenReturn(false);
        when(skuCatalogRepository.existsBySkuCode(42L, null)).thenReturn(false);
        when(skuCatalogRepository.existsByBarcode(null, null)).thenReturn(false);
        when(skuCatalogRepository.existsByNameAndSpec("可乐", null, null)).thenReturn(false);
        when(skuCatalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userInfoRepository.findById(10001L)).thenReturn(java.util.Optional.empty());

        var dto = service.createSku(10001L, minimalRequest(null));

        assertEquals("42", dto.skuId());
        assertEquals(42L, dto.skuCode());
        verify(skuCatalogRepository).save(any());
    }

    private static UpsertSkuRequest minimalRequest(String skuId) {
        return new UpsertSkuRequest(
                skuId,
                "可乐",
                350,
                500,
                true,
                null,
                null,
                null,
                null,
                "ACTIVE",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "件",
                // CB-061：件装数（每件的支数）；默认 1 表示无箱规
                1,
                // V349：经营归属
                null,
                // V350（CB-037）：maxPriceCents / minPriceCents
                null,
                null,
                // CB-049 扩展：商品必须至少配一个场景限额
                java.util.List.of(new com.aicabinet.common.dto.SkuSceneLimitItem("OFFICE", 5)));
    }

    @Test
    void createSku_persistsOperationScope() {
        when(skuCatalogRepository.nextSkuCode()).thenReturn(43L);
        when(skuCatalogRepository.existsById("43")).thenReturn(false);
        when(skuCatalogRepository.existsBySkuCode(43L, null)).thenReturn(false);
        when(skuCatalogRepository.existsByBarcode(null, null)).thenReturn(false);
        when(skuCatalogRepository.existsByNameAndSpec("可乐", null, null)).thenReturn(false);
        when(skuCatalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpsertSkuRequest req = new UpsertSkuRequest(
                null, "可乐", 350, 500, true,
                null, null, null, null, "ACTIVE",
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, "件", 1, "NON_DIRECT", null, null,
                java.util.List.of(new com.aicabinet.common.dto.SkuSceneLimitItem("OFFICE", 5)));

        var dto = service.createSku(10001L, req);

        assertEquals("NON_DIRECT", dto.operationScope());
        org.mockito.ArgumentCaptor<SkuCatalog> captor =
                org.mockito.ArgumentCaptor.forClass(SkuCatalog.class);
        verify(skuCatalogRepository).save(captor.capture());
        assertEquals("NON_DIRECT", captor.getValue().getOperationScope());
    }

    @Test
    void updateSku_blankOperationScopeClearsValue() {
        SkuCatalog existing = new SkuCatalog();
        existing.setSkuId("311");
        existing.setSkuName("旧名");
        existing.setOperationScope("DIRECT");
        when(skuCatalogRepository.findById("311")).thenReturn(java.util.Optional.of(existing));
        when(skuCatalogRepository.existsByBarcode(null, "311")).thenReturn(false);
        when(skuCatalogRepository.existsByNameAndSpec("新名", null, "311")).thenReturn(false);
        when(skuCatalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userInfoRepository.findById(10001L)).thenReturn(java.util.Optional.empty());

        UpsertSkuRequest req = new UpsertSkuRequest(
                "311", "新名", 350, 500, true,
                null, null, null, null, "ACTIVE",
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, "件", 1, "  ", null, null,
                java.util.List.of(new com.aicabinet.common.dto.SkuSceneLimitItem("OFFICE", 5)));

        service.updateSku(10001L, "311", req);

        // 空白归属 = 清回未分类（NULL），不允许「空串」脏值入库
        org.mockito.ArgumentCaptor<SkuCatalog> captor =
                org.mockito.ArgumentCaptor.forClass(SkuCatalog.class);
        verify(skuCatalogRepository).save(captor.capture());
        assertEquals(null, captor.getValue().getOperationScope());
    }
}
