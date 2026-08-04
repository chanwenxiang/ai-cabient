package com.aicabinet.trade.service;

import com.aicabinet.common.dto.UpsertAliyunMappingRequest;
import com.aicabinet.common.dto.UpsertYoloMappingRequest;
import com.aicabinet.trade.domain.AliyunCategoryMapping;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.SkuVisionMapping;
import com.aicabinet.trade.mapper.AliyunCategoryMappingMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuVisionMappingMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class VisionMappingService {

    private final SkuVisionMappingMapper yoloRepository;
    private final AliyunCategoryMappingMapper aliyunRepository;
    private final SkuCatalogMapper skuCatalogRepository;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;

    public VisionMappingService(SkuVisionMappingMapper yoloRepository,
                                AliyunCategoryMappingMapper aliyunRepository,
                                SkuCatalogMapper skuCatalogRepository,
                                PermissionService permissionService,
                                AdminAuditService auditService) {
        this.yoloRepository = yoloRepository;
        this.aliyunRepository = aliyunRepository;
        this.skuCatalogRepository = skuCatalogRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public VisionMappingsDto listMappings() {
        return buildDto();
    }

    @Transactional(readOnly = true)
    public VisionMappingsDto listMappingsForAdmin(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:vision:list");
        return buildDto();
    }

    @Transactional
    public YoloMappingDto upsertYolo(Long operatorId, UpsertYoloMappingRequest request) {
        permissionService.requirePermission(operatorId, "ops:vision:edit");
        requireSku(request.skuId());
        String className = request.className().trim();
        SkuVisionMapping mapping = yoloRepository.findById(className).orElse(new SkuVisionMapping());
        mapping.setClassName(className);
        mapping.setSkuId(request.skuId().trim());
        mapping.setMinConfidence(request.minConfidence());
        String source = request.mappingSource();
        mapping.setMappingSource(source == null || source.isBlank() ? "YOLO_COCO" : source.trim());
        yoloRepository.save(mapping);
        auditService.record(operatorId, "VISION_YOLO_UPSERT", "VISION", className,
                "sku=" + mapping.getSkuId() + " conf=" + mapping.getMinConfidence());
        return new YoloMappingDto(
                mapping.getClassName(), mapping.getSkuId(), mapping.getMinConfidence(), mapping.getMappingSource());
    }

    @Transactional
    public void deleteYolo(Long operatorId, String className) {
        permissionService.requirePermission(operatorId, "ops:vision:edit");
        if (!yoloRepository.existsById(className)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST);
        }
        yoloRepository.deleteById(className);
        auditService.record(operatorId, "VISION_YOLO_DELETE", "VISION", className, null);
    }

    @Transactional
    public AliyunMappingDto upsertAliyun(Long operatorId, UpsertAliyunMappingRequest request) {
        permissionService.requirePermission(operatorId, "ops:vision:edit");
        requireSku(request.skuId());
        String categoryId = request.categoryId().trim();
        AliyunCategoryMapping mapping = aliyunRepository.findById(categoryId).orElse(new AliyunCategoryMapping());
        mapping.setCategoryId(categoryId);
        mapping.setCategoryName(request.categoryName());
        mapping.setSkuId(request.skuId().trim());
        mapping.setMinConfidence(request.minConfidence());
        aliyunRepository.save(mapping);
        auditService.record(operatorId, "VISION_ALIYUN_UPSERT", "VISION", categoryId,
                "sku=" + mapping.getSkuId());
        return new AliyunMappingDto(
                mapping.getCategoryId(), mapping.getCategoryName(), mapping.getSkuId(), mapping.getMinConfidence());
    }

    @Transactional
    public void deleteAliyun(Long operatorId, String categoryId) {
        permissionService.requirePermission(operatorId, "ops:vision:edit");
        if (!aliyunRepository.existsById(categoryId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.INVALID_REQUEST);
        }
        aliyunRepository.deleteById(categoryId);
        auditService.record(operatorId, "VISION_ALIYUN_DELETE", "VISION", categoryId, null);
    }

    private void requireSku(String skuId) {
        if (!skuCatalogRepository.existsById(skuId.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.SKU_NOT_FOUND);
        }
    }

    private VisionMappingsDto buildDto() {
        Map<String, SkuCatalog> skuById = skuCatalogRepository.findAll().stream()
                .collect(Collectors.toMap(SkuCatalog::getSkuId, s -> s, (a, b) -> a));
        List<YoloMappingDto> yolo = yoloRepository.findAll().stream()
                .map(m -> {
                    SkuCatalog sku = skuById.get(m.getSkuId());
                    String enrollment = sku != null ? sku.getVisionEnrollmentStatus() : null;
                    boolean effective = enrollment != null && "PRODUCTION".equalsIgnoreCase(enrollment);
                    return new YoloMappingDto(
                            m.getClassName(),
                            m.getSkuId(),
                            m.getMinConfidence(),
                            m.getMappingSource(),
                            sku != null ? sku.getSkuName() : null,
                            enrollment,
                            effective,
                            SkuVisionEnrollmentService.MODEL_PIPELINE_WAITING);
                })
                .toList();
        List<AliyunMappingDto> aliyun = aliyunRepository.findAll().stream()
                .map(m -> new AliyunMappingDto(
                        m.getCategoryId(), m.getCategoryName(), m.getSkuId(), m.getMinConfidence()))
                .toList();
        return new VisionMappingsDto(yolo, aliyun);
    }

    public record YoloMappingDto(
            String className,
            String skuId,
            float minConfidence,
            String mappingSource,
            String skuName,
            String visionEnrollmentStatus,
            boolean mappingEffective,
            String modelPipelineStatus
    ) {
        public YoloMappingDto(String className, String skuId, float minConfidence, String mappingSource) {
            this(className, skuId, minConfidence, mappingSource, null, null, false,
                    SkuVisionEnrollmentService.MODEL_PIPELINE_WAITING);
        }
    }

    public record AliyunMappingDto(String categoryId, String categoryName, String skuId, float minConfidence) {}

    public record VisionMappingsDto(List<YoloMappingDto> yolo, List<AliyunMappingDto> aliyun) {}
}
