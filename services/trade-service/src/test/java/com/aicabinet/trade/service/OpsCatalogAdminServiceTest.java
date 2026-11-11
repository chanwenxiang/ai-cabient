package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertSkuRequest;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.AliyunCategoryMappingMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
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

    static {
        // R15-L1 守卫引入 LambdaQueryWrapper（existsByImageUrl）：纯 Mockito 环境需手动注册 TableInfo 缓存
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                SkuCatalog.class);
    }

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
                new SkuSceneLimitService(skuSceneLimitRepository, skuCatalogRepository, org.mockito.Mockito.mock(com.aicabinet.trade.mapper.DeviceSkuInventoryMapper.class), sceneDictStub()));
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

    /** MA-R1：下架（status 变化）此前审计 detail 完全无痕——100016-18 何时下架全靠猜。 */
    @Test
    void updateSku_statusChangeRecordedInAuditDetail() {
        SkuCatalog existing = new SkuCatalog();
        existing.setSkuId("100016");
        existing.setSkuName("可乐330");
        existing.setStatus("ACTIVE");
        existing.setPriceCents(350);
        when(skuCatalogRepository.findById("100016")).thenReturn(java.util.Optional.of(existing));
        when(skuCatalogRepository.existsByBarcode(null, "100016")).thenReturn(false);
        when(skuCatalogRepository.existsByNameAndSpec("可乐330", null, "100016")).thenReturn(false);
        when(skuCatalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userInfoRepository.findById(10001L)).thenReturn(java.util.Optional.empty());

        UpsertSkuRequest req = new UpsertSkuRequest(
                "100016", "可乐330", 350, 500, true,
                null, null, null, null, "INACTIVE",
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, "件", 1, null, null, null,
                java.util.List.of(new com.aicabinet.common.dto.SkuSceneLimitItem("OFFICE", 5)));

        service.updateSku(10001L, "100016", req);

        org.mockito.ArgumentCaptor<String> detailCaptor =
                org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditService).appendLog(org.mockito.ArgumentMatchers.eq(10001L),
                org.mockito.ArgumentMatchers.eq("SKU_UPDATE"),
                org.mockito.ArgumentMatchers.eq("SKU"),
                org.mockito.ArgumentMatchers.eq("100016"),
                detailCaptor.capture());
        String detail = detailCaptor.getValue();
        org.junit.jupiter.api.Assertions.assertTrue(detail.contains("status:ACTIVE->INACTIVE"),
                "audit detail must record status transition, got: " + detail);
    }

    /**
     * R15-L1：uploadSkuImage 按 sha256 去重 ⇒ 多个 SKU 共用同一 fileId，
     * 而 releaseSkuImageIfUnused 只数附件行（去重后恒 1 行）。
     * 档案层仍有 SKU 引用旧图时，换图操作不得释放该图（否则另一 SKU 的图被物理删掉）。
     */
    @Test
    void updateSku_sharedImageNotReleased() {
        SkuCatalog existing = new SkuCatalog();
        existing.setSkuId("100016");
        existing.setSkuName("可乐330");
        existing.setStatus("ACTIVE");
        existing.setPriceCents(350);
        existing.setImageUrl("/api/v2/media/sku-images/100");
        when(skuCatalogRepository.findById("100016")).thenReturn(java.util.Optional.of(existing));
        when(skuCatalogRepository.existsByBarcode(null, "100016")).thenReturn(false);
        when(skuCatalogRepository.existsByNameAndSpec("可乐330", null, "100016")).thenReturn(false);
        // SKU-A 仍引用同一 URL（sha 去重共享 fileId）
        when(skuCatalogRepository.existsByImageUrl("/api/v2/media/sku-images/100", null)).thenReturn(true);
        when(skuCatalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userInfoRepository.findById(10001L)).thenReturn(java.util.Optional.empty());

        UpsertSkuRequest req = new UpsertSkuRequest(
                "100016", "可乐330", 350, 500, true,
                "/api/v2/media/sku-images/200", null, null, null, "ACTIVE",
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, "件", 1, null, null, null,
                java.util.List.of(new com.aicabinet.common.dto.SkuSceneLimitItem("OFFICE", 5)));

        service.updateSku(10001L, "100016", req);

        verify(fileAttachmentService, never()).releaseSkuImageIfUnused(any());
    }

    /** R15-L1 反向：档案层确无引用（clearExplicitlyBlankColumns 已先行落库）才允许释放。 */
    @Test
    void updateSku_unreferencedOldImageReleased() {
        SkuCatalog existing = new SkuCatalog();
        existing.setSkuId("100016");
        existing.setSkuName("可乐330");
        existing.setStatus("ACTIVE");
        existing.setPriceCents(350);
        existing.setImageUrl("/api/v2/media/sku-images/100");
        when(skuCatalogRepository.findById("100016")).thenReturn(java.util.Optional.of(existing));
        when(skuCatalogRepository.existsByBarcode(null, "100016")).thenReturn(false);
        when(skuCatalogRepository.existsByNameAndSpec("可乐330", null, "100016")).thenReturn(false);
        when(skuCatalogRepository.existsByImageUrl("/api/v2/media/sku-images/100", null)).thenReturn(false);
        when(skuCatalogRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(userInfoRepository.findById(10001L)).thenReturn(java.util.Optional.empty());

        UpsertSkuRequest req = new UpsertSkuRequest(
                "100016", "可乐330", 350, 500, true,
                "/api/v2/media/sku-images/200", null, null, null, "ACTIVE",
                null, null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, "件", 1, null, null, null,
                java.util.List.of(new com.aicabinet.common.dto.SkuSceneLimitItem("OFFICE", 5)));

        service.updateSku(10001L, "100016", req);

        verify(fileAttachmentService).releaseSkuImageIfUnused("/api/v2/media/sku-images/100");
    }

    /** V404 字典门桩：字典恒空 ⇒ 服务回落 KNOWN_SCENES（与改造前行为一致） */
    private static SysDictService sceneDictStub() {
        SysDictService stub = org.mockito.Mockito.mock(SysDictService.class);
        org.mockito.Mockito.lenient().when(stub.hasActiveItems(SysDictService.DEVICE_SCENE_TYPE)).thenReturn(false);
        org.mockito.Mockito.lenient().when(stub.isKnownDictValue(org.mockito.ArgumentMatchers.eq(SysDictService.DEVICE_SCENE_TYPE),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anySet()))
            .thenAnswer(inv -> {
                String v = inv.getArgument(1);
                @SuppressWarnings("unchecked")
                java.util.Set<String> fb = (java.util.Set<String>) inv.getArgument(2);
                return v != null && fb.contains(v.trim());
            });
        return stub;
    }
}
