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

    private OpsCatalogAdminService service;

    @BeforeEach
    void setUp() {
        service = new OpsCatalogAdminService(
                permissionService, skuCatalogRepository, aliyunCategoryMappingRepository,
                userInfoRepository, auditService, fileAttachmentService);
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
                null);
    }
}
