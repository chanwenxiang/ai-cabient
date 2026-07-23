package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceVisionContextDto;
import com.aicabinet.common.dto.SkuCatalogDto;
import com.aicabinet.common.dto.SkuVisionContextItemDto;
import com.aicabinet.common.dto.SkuVisionEnrollmentPipelineDto;
import com.aicabinet.common.dto.SkuVisionEnrollmentRowDto;
import com.aicabinet.common.dto.UpsertSkuRequest;
import com.aicabinet.common.dto.UpsertSkuVisionEnrollmentRequest;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.config.StagingProperties;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.SkuVisionMapping;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuVisionMappingMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** 商品主数据与 YOLO 识别映射一体化录入、设备 SKU 白名单。 */
@Service
public class SkuVisionEnrollmentService {

    public static final String MODEL_PIPELINE_WAITING = "WAITING_REAL_MODEL";
    public static final String MODEL_PIPELINE_HINT =
            "真实 YOLO 训练与权重发布尚未接入；「生产」仅表示运营映射对结算白名单生效，识别仍可能为 mock/人工复核。";

    private static final List<String> STATUS_ORDER = List.of("DRAFT", "MAPPING", "TESTED", "PRODUCTION");
    private static final Set<String> ALLOWED_STATUS = Set.copyOf(STATUS_ORDER);

    private final SkuCatalogMapper skuCatalogRepository;
    private final SkuVisionMappingMapper yoloRepository;
    private final DeviceSlotService deviceSlotService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final StagingProperties stagingProperties;
    private final ObjectMapper objectMapper;
    private final VisionServiceClient visionServiceClient;

    public SkuVisionEnrollmentService(SkuCatalogMapper skuCatalogRepository,
                                        SkuVisionMappingMapper yoloRepository,
                                        DeviceSlotService deviceSlotService,
                                        PermissionService permissionService,
                                        AdminAuditService auditService,
                                        StagingProperties stagingProperties,
                                        ObjectMapper objectMapper,
                                        VisionServiceClient visionServiceClient) {
        this.skuCatalogRepository = skuCatalogRepository;
        this.yoloRepository = yoloRepository;
        this.deviceSlotService = deviceSlotService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.stagingProperties = stagingProperties;
        this.objectMapper = objectMapper;
        this.visionServiceClient = visionServiceClient;
    }

    @Transactional(readOnly = true)
    public List<SkuCatalogDto> listSkusWithVision(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:sku:list");
        return skuCatalogRepository.findAllByOrderBySkuIdAsc().stream()
                .map(SkuCatalog::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SkuVisionEnrollmentRowDto> listEnrollmentRows(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:sku:list");
        return skuCatalogRepository.findAllByOrderBySkuIdAsc().stream()
                .map(this::toEnrollmentRow)
                .toList();
    }

    @Transactional(readOnly = true)
    public SkuVisionEnrollmentPipelineDto pipelineMeta(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:sku:list");
        return new SkuVisionEnrollmentPipelineDto(
                MODEL_PIPELINE_WAITING,
                MODEL_PIPELINE_HINT,
                STATUS_ORDER,
                List.of(
                        new SkuVisionEnrollmentPipelineDto.StatusStepDto(
                                "DRAFT", "草稿", "录入商品基本信息，尚未绑定识别类名"),
                        new SkuVisionEnrollmentPipelineDto.StatusStepDto(
                                "MAPPING", "映射中", "已绑定 YOLO 类名与阈值，等待识别抽检"),
                        new SkuVisionEnrollmentPipelineDto.StatusStepDto(
                                "TESTED", "已测试", "运营已完成识别预览抽检（可为 mock）"),
                        new SkuVisionEnrollmentPipelineDto.StatusStepDto(
                                "PRODUCTION", "生产（映射生效）", "进入结算白名单；模型侧仍为等待真实训练")
                )
        );
    }

    @Transactional
    public SkuCatalogDto enrollSku(Long operatorId, UpsertSkuVisionEnrollmentRequest request) {
        permissionService.requirePermission(operatorId, "ops:sku:edit");
        permissionService.requirePermission(operatorId, "ops:vision:edit");
        UpsertSkuRequest skuReq = request.sku();
        String skuId = skuReq.skuId().trim();
        SkuCatalog sku = skuCatalogRepository.findById(skuId).orElseGet(SkuCatalog::new);
        boolean created = sku.getSkuId() == null;
        if (created) {
            sku.setSkuId(skuId);
        }
        applySkuFields(sku, skuReq, request);
        skuCatalogRepository.save(sku);

        String className = resolveClassName(request.yoloClassName(), sku);
        SkuVisionMapping mapping = yoloRepository.findById(className).orElse(new SkuVisionMapping());
        mapping.setClassName(className);
        mapping.setSkuId(skuId);
        mapping.setMinConfidence(request.detectionMinConfidence());
        mapping.setMappingSource(request.mappingSource());
        yoloRepository.save(mapping);

        auditService.record(operatorId, created ? "SKU_VISION_ENROLL_CREATE" : "SKU_VISION_ENROLL_UPDATE",
                "SKU", skuId, "class=" + className + " status=" + sku.getVisionEnrollmentStatus());
        return sku.toDto();
    }

    @Transactional
    public SkuCatalogDto updateEnrollmentStatus(Long operatorId, String skuId, String status) {
        permissionService.requirePermission(operatorId, "ops:vision:edit");
        SkuCatalog sku = requireSku(skuId);
        String next = normalizeStatus(status);
        assertAllowedStatus(next);
        if ("PRODUCTION".equals(next) || "TESTED".equals(next) || "MAPPING".equals(next)) {
            requireMappedClass(sku);
        }
        sku.setVisionEnrollmentStatus(next);
        skuCatalogRepository.save(sku);
        auditService.record(operatorId, "SKU_VISION_STATUS", "SKU", skuId,
                next + " pipeline=" + MODEL_PIPELINE_WAITING);
        return sku.toDto();
    }

    /** 按固定顺序推进：DRAFT→MAPPING→TESTED→PRODUCTION。 */
    @Transactional
    public SkuVisionEnrollmentRowDto advanceEnrollment(Long operatorId, String skuId) {
        permissionService.requirePermission(operatorId, "ops:vision:edit");
        SkuCatalog sku = requireSku(skuId);
        String current = normalizeStatus(sku.getVisionEnrollmentStatus());
        String next = nextStatus(current);
        if (next == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "已处于生产状态，无需再推进（模型管线仍为 " + MODEL_PIPELINE_WAITING + "）");
        }
        if ("MAPPING".equals(next) || "TESTED".equals(next) || "PRODUCTION".equals(next)) {
            requireMappedClass(sku);
        }
        sku.setVisionEnrollmentStatus(next);
        skuCatalogRepository.save(sku);
        auditService.record(operatorId, "SKU_VISION_ADVANCE", "SKU", skuId,
                current + "→" + next + " pipeline=" + MODEL_PIPELINE_WAITING);
        return toEnrollmentRow(sku);
    }

    @Transactional(readOnly = true)
    public DeviceVisionContextDto deviceVisionContext(String deviceId) {
        Map<String, Integer> qtyBySku = deviceSlotService.inventorySnapshot(deviceId).stream()
                .collect(Collectors.toMap(
                        q -> q.skuId(),
                        q -> q.quantity(),
                        Integer::sum,
                        LinkedHashMap::new));
        if (qtyBySku.isEmpty()) {
            return new DeviceVisionContextDto(deviceId, List.of());
        }
        List<SkuVisionContextItemDto> items = new ArrayList<>();
        for (SkuCatalog sku : skuCatalogRepository.findAllById(qtyBySku.keySet())) {
            if (!sku.isVisionEnabled() || !"ACTIVE".equalsIgnoreCase(sku.getStatus())) {
                continue;
            }
            items.add(toContextItem(sku, qtyBySku.getOrDefault(sku.getSkuId(), 0)));
        }
        items.sort((a, b) -> a.skuId().compareTo(b.skuId()));
        return new DeviceVisionContextDto(deviceId, items);
    }

    @Transactional(readOnly = true)
    public List<CatalogClassRow> listCatalogClassesForTraining() {
        return skuCatalogRepository.findAllByOrderBySkuIdAsc().stream()
                .filter(s -> "ACTIVE".equalsIgnoreCase(s.getStatus()))
                .map(s -> new CatalogClassRow(
                        s.getSkuId(),
                        (s.getYoloClassName() != null && !s.getYoloClassName().isBlank())
                                ? s.getYoloClassName()
                                : suggestClassName(s.getSkuName()),
                        s.getSkuName()))
                .toList();
    }

    /** 结算前校验：识别 SKU 须在柜内库存且 enrollment=PRODUCTION。沙箱/预发可跳过。 */
    @Transactional(readOnly = true)
    public Optional<String> validateSettlementItems(String deviceId,
                                                    List<VisionServiceClient.RecognizedItem> items) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        if (stagingProperties.stagingMode() || stagingProperties.gravityFallbackSettle()) {
            return Optional.empty();
        }
        DeviceVisionContextDto ctx = deviceVisionContext(deviceId);
        Map<String, SkuVisionContextItemDto> allowed = ctx.skus().stream()
                .collect(Collectors.toMap(SkuVisionContextItemDto::skuId, s -> s, (a, b) -> a));
        for (VisionServiceClient.RecognizedItem item : items) {
            SkuVisionContextItemDto row = allowed.get(item.skuId());
            if (row == null) {
                return Optional.of("识别 SKU " + item.skuId() + " 不在柜机 " + deviceId + " 在售白名单");
            }
            if (!"PRODUCTION".equalsIgnoreCase(row.visionEnrollmentStatus())) {
                return Optional.of("SKU " + item.skuId() + " 视觉状态为 "
                        + row.visionEnrollmentStatus() + "，不可自动扣款");
            }
        }
        return Optional.empty();
    }

    public java.util.Map<String, Object> suggestClassFromImage(byte[] imageBytes, String filename, String skuName) {
        return visionServiceClient.suggestClassFromImage(imageBytes, filename, skuName);
    }

    public VisionServiceClient.RecognitionResult suggestDisputeSkus(
            String deviceId, byte[] imageBytes, String filename) {
        return visionServiceClient.suggestDisputeSkus(deviceId, imageBytes, filename);
    }

    public static String suggestClassName(String skuName) {
        if (skuName == null || skuName.isBlank()) {
            return "sku_unknown";
        }
        String slug = skuName.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (slug.isBlank()) {
            return "sku_unknown";
        }
        if (slug.length() > 48) {
            slug = slug.substring(0, 48);
        }
        return slug;
    }

    private SkuVisionContextItemDto toContextItem(SkuCatalog sku, int qty) {
        return new SkuVisionContextItemDto(
                sku.getSkuId(),
                sku.getSkuName(),
                sku.getPriceCents(),
                sku.getYoloClassName(),
                sku.getImageUrl(),
                parseReferenceUrls(sku.getReferenceImageUrlsJson()),
                sku.getDetectionMinConfidence(),
                sku.getVisionEnrollmentStatus(),
                qty
        );
    }

    private List<String> parseReferenceUrls(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception ignored) {
            return List.of(json.trim());
        }
    }

    private void applySkuFields(SkuCatalog sku, UpsertSkuRequest skuReq, UpsertSkuVisionEnrollmentRequest request) {
        sku.setSkuName(skuReq.skuName().trim());
        sku.setPriceCents(skuReq.priceCents());
        sku.setWeightGrams(skuReq.weightGrams());
        sku.setVisionEnabled(skuReq.visionEnabled());
        sku.setImageUrl(trimToNull(skuReq.imageUrl()));
        sku.setDescription(trimToNull(skuReq.description()));
        sku.setCategory(trimToNull(skuReq.category()));
        sku.setBarcode(trimToNull(skuReq.barcode()));
        sku.setStatus(skuReq.status());
        sku.setShelfLifeDays(skuReq.shelfLifeDays());
        sku.setNearExpiryDays(skuReq.nearExpiryDays());
        sku.setBlockSaleDaysBeforeExpiry(skuReq.blockSaleDaysBeforeExpiry());
        sku.setStorageType(skuReq.storageType());
        sku.setPurchaseCostCents(skuReq.purchaseCostCents());
        sku.setNearExpiryPriceCents(skuReq.nearExpiryPriceCents());
        if (skuReq.minChargeConfidence() != null) {
            sku.setMinChargeConfidence(skuReq.minChargeConfidence());
        }
        if (skuReq.yoloClassName() != null && !skuReq.yoloClassName().isBlank()) {
            sku.setYoloClassName(skuReq.yoloClassName().trim());
        } else if (request.yoloClassName() != null && !request.yoloClassName().isBlank()) {
            sku.setYoloClassName(request.yoloClassName().trim());
        } else if (sku.getYoloClassName() == null || sku.getYoloClassName().isBlank()) {
            sku.setYoloClassName(suggestClassName(sku.getSkuName()));
        }
        if (skuReq.visionEnrollmentStatus() != null && !skuReq.visionEnrollmentStatus().isBlank()) {
            sku.setVisionEnrollmentStatus(normalizeStatus(skuReq.visionEnrollmentStatus()));
        } else if (request.visionEnrollmentStatus() != null) {
            sku.setVisionEnrollmentStatus(normalizeStatus(request.visionEnrollmentStatus()));
        }
        if (skuReq.detectionMinConfidence() != null) {
            sku.setDetectionMinConfidence(skuReq.detectionMinConfidence());
        } else if (request.detectionMinConfidence() != null) {
            sku.setDetectionMinConfidence(request.detectionMinConfidence());
        }
        if (skuReq.referenceImageUrlsJson() != null) {
            sku.setReferenceImageUrlsJson(trimToNull(skuReq.referenceImageUrlsJson()));
        } else if (request.referenceImageUrlsJson() != null) {
            sku.setReferenceImageUrlsJson(trimToNull(request.referenceImageUrlsJson()));
        }
    }

    private String resolveClassName(String requestClass, SkuCatalog sku) {
        if (requestClass != null && !requestClass.isBlank()) {
            return requestClass.trim();
        }
        if (sku.getYoloClassName() != null && !sku.getYoloClassName().isBlank()) {
            return sku.getYoloClassName().trim();
        }
        return suggestClassName(sku.getSkuName());
    }

    private SkuCatalog requireSku(String skuId) {
        return skuCatalogRepository.findById(skuId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SKU_NOT_FOUND));
    }

    private static String normalizeStatus(String status) {
        return status == null ? "DRAFT" : status.trim().toUpperCase(Locale.ROOT);
    }

    private static void assertAllowedStatus(String status) {
        if (!ALLOWED_STATUS.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "无效识别状态，允许: " + String.join("/", STATUS_ORDER));
        }
    }

    private static String nextStatus(String current) {
        int idx = STATUS_ORDER.indexOf(current);
        if (idx < 0) {
            return "MAPPING";
        }
        if (idx >= STATUS_ORDER.size() - 1) {
            return null;
        }
        return STATUS_ORDER.get(idx + 1);
    }

    private void requireMappedClass(SkuCatalog sku) {
        if (sku.getYoloClassName() == null || sku.getYoloClassName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "请先保存识别类名映射后再推进状态");
        }
        if (!yoloRepository.existsById(sku.getYoloClassName().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "识别类名尚未写入映射表，请先保存「商品与识别」入驻");
        }
    }

    private SkuVisionEnrollmentRowDto toEnrollmentRow(SkuCatalog sku) {
        String status = normalizeStatus(sku.getVisionEnrollmentStatus());
        boolean mappingEffective = "PRODUCTION".equals(status)
                && sku.getYoloClassName() != null
                && !sku.getYoloClassName().isBlank();
        String next = nextStatus(status);
        String nextAction = switch (status) {
            case "DRAFT" -> "保存类名映射并推进到「映射中」";
            case "MAPPING" -> "完成识别抽检后推进到「已测试」";
            case "TESTED" -> "确认转生产（映射生效，仍等待真实模型）";
            case "PRODUCTION" -> "映射已生效；等待真实模型训练接入";
            default -> "检查识别入驻状态";
        };
        return new SkuVisionEnrollmentRowDto(
                sku.toDto(),
                mappingEffective,
                MODEL_PIPELINE_WAITING,
                nextAction,
                next
        );
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record CatalogClassRow(String skuId, String className, String skuName) {}
}
