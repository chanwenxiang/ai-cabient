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

    public OpsCatalogAdminService(PermissionService permissionService,
                                  SkuCatalogMapper skuCatalogRepository,
                                  AliyunCategoryMappingMapper aliyunCategoryMappingRepository,
                                  UserInfoMapper userInfoRepository,
                                  AdminAuditService auditService,
                                  FileAttachmentService fileAttachmentService) {
        this.permissionService = permissionService;
        this.skuCatalogRepository = skuCatalogRepository;
        this.aliyunCategoryMappingRepository = aliyunCategoryMappingRepository;
        this.userInfoRepository = userInfoRepository;
        this.auditService = auditService;
        this.fileAttachmentService = fileAttachmentService;
    }

    public List<SkuCatalogDto> listSkus(Long operatorId) {
        return listSkus(operatorId, null, null, null);
    }

    @Transactional(readOnly = true)
    public List<SkuCatalogDto> listSkus(Long operatorId, String q, String status, String category) {
        permissionService.requireAnyPermission(operatorId, "ops:sku:list", "ops:replenishment:list", "ops:warehouse:list");
        return listSkusPage(operatorId, q, status, category, 0, 500).items();
    }

    @Transactional(readOnly = true)
    public PageResult<SkuCatalogDto> listSkusPage(
            Long operatorId, String q, String status, String category, int page, int size) {
        permissionService.requireAnyPermission(operatorId, "ops:sku:list", "ops:replenishment:list", "ops:warehouse:list");
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 500);
        var result = skuCatalogRepository.search(q, status, category, p, s);
        List<SkuCatalogDto> items = result.getRecords().stream().map(SkuCatalog::toDto).toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional
    public SkuCatalogDto createSku(Long operatorId, UpsertSkuRequest request) {
        permissionService.requirePermission(operatorId, "ops:sku:edit");
        long code = skuCatalogRepository.nextSkuCode();
        String skuId = request.skuId() != null && !request.skuId().isBlank()
                ? request.skuId().trim()
                : "SKU-" + code;
        if (skuCatalogRepository.existsById(skuId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_EXISTS);
        }
        String barcode = trimToNull(request.barcode());
        assertBarcodeUnique(barcode, null);
        assertSkuNameUnique(request.skuName(), null);
        SkuCatalog sku = new SkuCatalog();
        sku.setSkuId(skuId);
        sku.setSkuCode(code);
        applySkuRequest(sku, request);
        syncSkuCategoryId(sku);
        touchSkuUpdater(sku, operatorId);
        if (sku.getCreatedAt() == null) {
            sku.setCreatedAt(Instant.now());
        }
        skuCatalogRepository.save(sku);
        auditService.appendLog(operatorId, "SKU_CREATE", "SKU", sku.getSkuId(),
                "code=" + sku.getSkuCode() + " " + sku.getSkuName() + " price=" + sku.getPriceCents());
        return sku.toDto();
    }

    @Transactional
    public SkuCatalogDto updateSku(Long operatorId, String skuId, UpsertSkuRequest request) {
        permissionService.requirePermission(operatorId, "ops:sku:edit");
        SkuCatalog sku = skuCatalogRepository.findById(skuId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SKU_NOT_FOUND));
        String oldImageUrl = sku.getImageUrl();
        String barcode = trimToNull(request.barcode());
        assertBarcodeUnique(barcode, skuId);
        assertSkuNameUnique(request.skuName(), skuId);
        applySkuRequest(sku, request);
        syncSkuCategoryId(sku);
        touchSkuUpdater(sku, operatorId);
        skuCatalogRepository.save(sku);
        String newImageUrl = trimToNull(request.imageUrl());
        if (oldImageUrl != null && !oldImageUrl.equals(newImageUrl)) {
            fileAttachmentService.releaseSkuImageIfUnused(oldImageUrl);
        }
        auditService.appendLog(operatorId, "SKU_UPDATE", "SKU", sku.getSkuId(),
                "code=" + sku.getSkuCode() + " " + sku.getSkuName() + " price=" + sku.getPriceCents());
        return sku.toDto();
    }

    private void assertBarcodeUnique(String barcode, String excludeSkuId) {
        if (skuCatalogRepository.existsByBarcode(barcode, excludeSkuId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_BARCODE_EXISTS);
        }
    }

    private void assertSkuNameUnique(String skuName, String excludeSkuId) {
        if (skuCatalogRepository.existsBySkuName(skuName, excludeSkuId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_NAME_EXISTS);
        }
    }

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
        sku.setSpec(trimToNull(request.spec()));
        sku.setUnit(request.unit() != null && !request.unit().isBlank() ? request.unit().trim() : "件");
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
        if (request.yoloClassName() != null && !request.yoloClassName().isBlank()) {
            sku.setYoloClassName(request.yoloClassName().trim());
        }
        if (request.visionEnrollmentStatus() != null && !request.visionEnrollmentStatus().isBlank()) {
            sku.setVisionEnrollmentStatus(request.visionEnrollmentStatus().trim().toUpperCase());
        }
        if (request.detectionMinConfidence() != null) {
            sku.setDetectionMinConfidence(request.detectionMinConfidence());
        }
        if (request.referenceImageUrlsJson() != null) {
            sku.setReferenceImageUrlsJson(trimToNull(request.referenceImageUrlsJson()));
        }
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
