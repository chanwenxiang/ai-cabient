package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.SkuCatalogDto;
import com.aicabinet.common.dto.UpsertSkuRequest;
import com.aicabinet.trade.domain.AliyunCategoryMapping;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.AliyunCategoryMappingMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.aicabinet.trade.support.SkuIds;
import com.aicabinet.trade.support.SkuPriceBounds;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 运营 SKU 目录管理（原 AdminDashboardService 商品簇）。
 * Pass 3F：Analytics/Device 之后的低风险切片。
 */
@Service
public class OpsCatalogAdminService {

    private final PermissionService permissionService;
    private final SkuCatalogMapper skuCatalogRepository;
    private final AliyunCategoryMappingMapper aliyunCategoryMappingRepository;
    private final UserInfoMapper userInfoRepository;
    private final AdminAuditService auditService;
    private final FileAttachmentService fileAttachmentService;
    private final SkuSceneLimitService skuSceneLimitService;

    public OpsCatalogAdminService(PermissionService permissionService,
                                  SkuCatalogMapper skuCatalogRepository,
                                  AliyunCategoryMappingMapper aliyunCategoryMappingRepository,
                                  UserInfoMapper userInfoRepository,
                                  AdminAuditService auditService,
                                  FileAttachmentService fileAttachmentService,
                                  SkuSceneLimitService skuSceneLimitService) {
        this.permissionService = permissionService;
        this.skuCatalogRepository = skuCatalogRepository;
        this.aliyunCategoryMappingRepository = aliyunCategoryMappingRepository;
        this.userInfoRepository = userInfoRepository;
        this.auditService = auditService;
        this.fileAttachmentService = fileAttachmentService;
        this.skuSceneLimitService = skuSceneLimitService;
    }

    public List<SkuCatalogDto> listSkus(Long operatorId) {
        return listSkus(operatorId, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<SkuCatalogDto> listSkus(Long operatorId, String q, String status, String category) {
        return listSkus(operatorId, q, status, category, null);
    }

    @Transactional(readOnly = true)
    public List<SkuCatalogDto> listSkus(
            Long operatorId, String q, String status, String category, String operationScope) {
        permissionService.requireAnyPermission(operatorId, "ops:sku:list", "ops:replenishment:list", "ops:warehouse:list");
        return listSkusPage(operatorId, q, status, category, operationScope, 0, 500).items();
    }

    @Transactional(readOnly = true)
    public PageResult<SkuCatalogDto> listSkusPage(
            Long operatorId, String q, String status, String category, int page, int size) {
        return listSkusPage(operatorId, q, status, category, null, page, size);
    }

    @Transactional(readOnly = true)
    public PageResult<SkuCatalogDto> listSkusPage(
            Long operatorId, String q, String status, String category,
            String operationScope, int page, int size) {
        permissionService.requireAnyPermission(operatorId, "ops:sku:list", "ops:replenishment:list", "ops:warehouse:list");
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 500);
        var result = skuCatalogRepository.search(q, status, category, operationScope, p, s);
        // 商品×场景限额批量回填（CB-049 扩展）：一查全量，避免 N+1
        var limits = skuSceneLimitService.limitsBySku(
                result.getRecords().stream().map(SkuCatalog::getSkuId).toList());
        List<SkuCatalogDto> items = result.getRecords().stream()
                .map(sku -> sku.toDto(limits.get(sku.getSkuId())))
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional
    public SkuCatalogDto createSku(Long operatorId, UpsertSkuRequest request) {
        permissionService.requirePermission(operatorId, "ops:sku:edit");
        AllocatedSkuId allocated = allocateSkuId(request.skuId());
        String barcode = trimToNull(request.barcode());
        assertBarcodeUnique(barcode, null);
        assertNameSpecUnique(request.skuName(), request.spec(), null);
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId(allocated.skuId());
        sku.setSkuCode(allocated.skuCode());
        applySkuRequest(sku, request);
        syncSkuCategoryId(sku);
        touchSkuUpdater(sku, operatorId);
        if (sku.getCreatedAt() == null) {
            sku.setCreatedAt(Instant.now());
        }
        skuCatalogRepository.save(sku);
        // 商品×场景限额（CB-049 扩展）：新建必须至少配一个场景；整单替换
        skuSceneLimitService.validateRequestItems(request.sceneLimits());
        skuSceneLimitService.replaceLimits(sku.getSkuId(), request.sceneLimits());
        auditService.appendLog(operatorId, "SKU_CREATE", "SKU", sku.getSkuId(),
                "code=" + sku.getSkuCode() + " " + sku.getSkuName() + " price=" + sku.getPriceCents());
        return sku.toDto(request.sceneLimits());
    }

    @Transactional
    public SkuCatalogDto updateSku(Long operatorId, String skuId, UpsertSkuRequest request) {
        permissionService.requirePermission(operatorId, "ops:sku:edit");
        SkuCatalog sku = skuCatalogRepository.findById(skuId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SKU_NOT_FOUND));
        String oldImageUrl = sku.getImageUrl();
        String barcode = trimToNull(request.barcode());
        assertBarcodeUnique(barcode, skuId);
        assertNameSpecUnique(request.skuName(), request.spec(), skuId);
        applySkuRequest(sku, request);
        syncSkuCategoryId(sku);
        touchSkuUpdater(sku, operatorId);
        skuCatalogRepository.save(sku);
        // 编辑路径：sceneLimits=null = 未提供（CSV 导入等局部更新）→ 保留既有限额；
        // 非 null（含空数组）= 整单替换（空数组被 validateRequestItems 拒绝，场景不可清空）
        if (request.sceneLimits() != null) {
            skuSceneLimitService.replaceLimits(sku.getSkuId(), request.sceneLimits());
        }
        String newImageUrl = trimToNull(request.imageUrl());
        if (oldImageUrl != null && !oldImageUrl.equals(newImageUrl)) {
            fileAttachmentService.releaseSkuImageIfUnused(oldImageUrl);
        }
        auditService.appendLog(operatorId, "SKU_UPDATE", "SKU", sku.getSkuId(),
                "code=" + sku.getSkuCode() + " " + sku.getSkuName() + " price=" + sku.getPriceCents());
        return sku.toDto(request.sceneLimits());
    }

    private void assertBarcodeUnique(String barcode, String excludeSkuId) {
        if (skuCatalogRepository.existsByBarcode(barcode, excludeSkuId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_BARCODE_EXISTS);
        }
    }

    private void assertNameSpecUnique(String skuName, String spec, String excludeSkuId) {
        if (skuCatalogRepository.existsByNameAndSpec(skuName, spec, excludeSkuId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_NAME_EXISTS);
        }
    }

    private AllocatedSkuId allocateSkuId(String requested) {
        String trimmed = trimToNull(requested);
        long code;
        String skuId;
        if (trimmed == null) {
            code = skuCatalogRepository.nextSkuCode();
            skuId = SkuIds.fromCode(code);
        } else {
            SkuIds.requireNumeric(trimmed);
            skuId = trimmed;
            code = Long.parseLong(trimmed);
        }
        if (skuCatalogRepository.existsById(skuId) || skuCatalogRepository.existsBySkuCode(code, null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_EXISTS);
        }
        return new AllocatedSkuId(skuId, code);
    }

    private record AllocatedSkuId(String skuId, long skuCode) {}

    static void applySkuRequest(SkuCatalog sku, UpsertSkuRequest request) {
        sku.setSkuName(request.skuName().trim());
        sku.setPriceCents(request.priceCents());
        sku.setWeightGrams(request.weightGrams());
        sku.setVisionEnabled(request.visionEnabled());
        sku.setImageUrl(trimToNull(request.imageUrl()));
        sku.setDescription(trimToNull(request.description()));
        sku.setCategory(trimToNull(request.category()));
        sku.setBarcode(trimToNull(request.barcode()));
        sku.setBrand(trimToNull(request.brand()));
        sku.setSpec(request.spec() == null || request.spec().isBlank() ? "" : request.spec().trim());
        sku.setUnit(request.unit() != null && !request.unit().isBlank() ? request.unit().trim() : "件");
        // CB-061：件装数（每件的支数），用于采购按件、库存/销售按支的换算；默认 1 表示无箱规。
        sku.setPackQty(request.packQty() > 0 ? request.packQty() : 1);
        sku.setStatus(request.status());
        sku.setShelfLifeDays(request.shelfLifeDays());
        sku.setNearExpiryDays(request.nearExpiryDays());
        sku.setBlockSaleDaysBeforeExpiry(request.blockSaleDaysBeforeExpiry());
        sku.setStorageType(request.storageType());
        sku.setPurchaseCostCents(request.purchaseCostCents());
        sku.setNearExpiryPriceCents(request.nearExpiryPriceCents());
        if (request.minChargeConfidence() != null) {
            sku.setMinChargeConfidence(request.minChargeConfidence());
        }
        if (request.detectionMinConfidence() != null) {
            sku.setDetectionMinConfidence(request.detectionMinConfidence());
        }
        // 经营归属（V349）：空=未分类；取值合法性由 UpsertSkuRequest @Pattern 兜底
        sku.setOperationScope(trimToNull(request.operationScope()));
        // 限价设置（V350，CB-037）：admin 侧首次获得 max/min 限价入口；自洽性 fail-closed
        SkuPriceBounds.validateBounds(request.minPriceCents(), request.maxPriceCents());
        sku.setMaxPriceCents(request.maxPriceCents());
        sku.setMinPriceCents(request.minPriceCents());
        // 全局基准价同样受限价约束（历史缺陷：admin 改基准价无任何校验，手抖全局生效）
        SkuPriceBounds.validatePrice(sku, request.priceCents());
    }

    private void touchSkuUpdater(SkuCatalog sku, Long operatorId) {
        if (operatorId == null || operatorId <= 0L) {
            sku.setUpdatedByUserId(null);
            sku.setUpdatedByName("系统");
            return;
        }
        sku.setUpdatedByUserId(operatorId);
        UserInfo user = userInfoRepository.findById(operatorId).orElse(null);
        String name = user != null ? user.getName() : null;
        String phone = user != null ? user.getPhoneNumber() : null;
        if (name == null || name.isBlank()) {
            name = phone != null && !phone.isBlank() ? phone : ("账号 " + operatorId);
        }
        sku.setUpdatedByName(name);
    }

    private void syncSkuCategoryId(SkuCatalog sku) {
        String category = sku.getCategory();
        if (category == null || category.isBlank()) {
            sku.setCategoryId(null);
            return;
        }
        AliyunCategoryMapping mapping = aliyunCategoryMappingRepository.selectOne(
                Wrappers.<AliyunCategoryMapping>lambdaQuery()
                        .eq(AliyunCategoryMapping::getCategoryName, category.trim())
                        .last("LIMIT 1"));
        sku.setCategoryId(mapping != null ? mapping.getCategoryId() : null);
    }

    static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
