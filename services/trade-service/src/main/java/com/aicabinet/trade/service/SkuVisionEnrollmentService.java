package com.aicabinet.trade.service;
import com.aicabinet.common.constants.CabinetConstants;

import com.aicabinet.common.dto.DeviceVisionContextDto;
import com.aicabinet.common.dto.PageResult;
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
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuVisionMappingMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.context.annotation.Lazy;
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

/** 商品主数据与端侧识别类名映射一体化录入、设备 SKU 白名单（算法无关，不绑定 YOLO）。 */
@Service
public class SkuVisionEnrollmentService {
    private static final String PERM_OPS_VISION_EDIT = "ops:vision:edit";
    private static final String PERM_OPS_SKU_LIST = "ops:sku:list";
    private static final String PRODUCTION = "PRODUCTION";
    private static final String MAPPING = "MAPPING";
    private static final String TESTED = "TESTED";


    /** 端侧识别提供方尚未声明生产就绪时的管线状态。 */
    public static final String MODEL_PIPELINE_WAITING = "WAITING_EDGE_PROVIDER";
    public static final String MODEL_PIPELINE_HINT =
            "「生产」仅表示运营映射进入结算白名单；实际自动扣款取决于端侧识别回传质量"
                    + "（mock/fallback/低置信会进争议）。类名映射算法无关，可随时换端侧识别提供方。";

    private static final List<String> STATUS_ORDER = List.of(CabinetConstants.PROMOTION_STATUS_DRAFT, MAPPING, TESTED, PRODUCTION);
    private static final Set<String> ALLOWED_STATUS = Set.copyOf(STATUS_ORDER);

    private final SkuCatalogMapper skuCatalogRepository;
    private final SkuVisionMappingMapper yoloRepository;
    private final DeviceSlotService deviceSlotService;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final StagingProperties stagingProperties;
    private final ObjectMapper objectMapper;
    private final VisionServiceClient visionServiceClient;
    private final UserInfoMapper userInfoRepository;
    private final FileAttachmentService fileAttachmentService;
    private final DistributedLockService distributedLockService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final SkuVisionEnrollmentService self;

    public SkuVisionEnrollmentService(SkuCatalogMapper skuCatalogRepository,
                                        SkuVisionMappingMapper yoloRepository,
                                        DeviceSlotService deviceSlotService,
                                        PermissionService permissionService,
                                        AdminAuditService auditService,
                                        StagingProperties stagingProperties,
                                        ObjectMapper objectMapper,
                                        VisionServiceClient visionServiceClient,
                                        UserInfoMapper userInfoRepository,
                                        FileAttachmentService fileAttachmentService,
                                        DistributedLockService distributedLockService, @Lazy SkuVisionEnrollmentService self) {
        this.skuCatalogRepository = skuCatalogRepository;
        this.yoloRepository = yoloRepository;
        this.deviceSlotService = deviceSlotService;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.stagingProperties = stagingProperties;
        this.objectMapper = objectMapper;
        this.visionServiceClient = visionServiceClient;
        this.userInfoRepository = userInfoRepository;
        this.fileAttachmentService = fileAttachmentService;
        this.distributedLockService = distributedLockService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public List<SkuCatalogDto> listSkusWithVision(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_SKU_LIST);
        return skuCatalogRepository.findAllByOrderBySkuCodeAsc().stream()
                .map(SkuCatalog::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SkuVisionEnrollmentRowDto> listEnrollmentRows(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_SKU_LIST);
        return skuCatalogRepository.findAllByOrderBySkuCodeAsc().stream()
                .map(this::toEnrollmentRow)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<SkuVisionEnrollmentRowDto> listEnrollmentRowsPage(
            Long operatorId, String q, String status, String enrollmentStatus, int page, int size) {
        permissionService.requirePermission(operatorId, PERM_OPS_SKU_LIST);
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        String saleStatus = status == null || status.isBlank() ? "ALL" : status.trim();
        var result = skuCatalogRepository.search(q, saleStatus, null, enrollmentStatus, p, s);
        List<SkuVisionEnrollmentRowDto> items = result.getRecords().stream()
                .map(this::toEnrollmentRow)
                .toList();
        return new PageResult<>(items, p, s, result.getTotal());
    }

    @Transactional(readOnly = true)
    public SkuVisionEnrollmentPipelineDto pipelineMeta(Long operatorId) {
        permissionService.requirePermission(operatorId, PERM_OPS_SKU_LIST);
        return new SkuVisionEnrollmentPipelineDto(
                MODEL_PIPELINE_WAITING,
                MODEL_PIPELINE_HINT,
                STATUS_ORDER,
                List.of(
                        new SkuVisionEnrollmentPipelineDto.StatusStepDto(
                                CabinetConstants.PROMOTION_STATUS_DRAFT, "草稿", "录入商品基本信息，尚未绑定端侧识别类名"),
                        new SkuVisionEnrollmentPipelineDto.StatusStepDto(
                                MAPPING, "映射中", "已绑定识别类名与阈值，等待端侧/联调抽检"),
                        new SkuVisionEnrollmentPipelineDto.StatusStepDto(
                                TESTED, "已测试", "运营已完成识别预览抽检（可用联调数据）"),
                        new SkuVisionEnrollmentPipelineDto.StatusStepDto(
                                PRODUCTION, "生产（结算白名单）",
                                "进入自动扣款白名单；端侧若回传 mock/fallback/低置信仍进争议")
                )
        );
    }

    @Transactional
    public SkuCatalogDto enrollSku(Long operatorId, UpsertSkuVisionEnrollmentRequest request) {
        permissionService.requirePermission(operatorId, "ops:sku:edit");
        permissionService.requirePermission(operatorId, PERM_OPS_VISION_EDIT);
        UpsertSkuRequest skuReq = request.sku();
        String requestedId = skuReq.skuId() != null ? skuReq.skuId().trim() : "";
        if (!requestedId.isEmpty()) {
            return runWithSkuVisionLock(requestedId, () -> doEnrollSku(operatorId, request, requestedId));
        }
        return doEnrollSku(operatorId, request, requestedId);
    }

    private SkuCatalogDto doEnrollSku(Long operatorId, UpsertSkuVisionEnrollmentRequest request, String requestedId) {
        UpsertSkuRequest skuReq = request.sku();
        SkuCatalog sku = requestedId.isEmpty()
                ? new SkuCatalog()
                : skuCatalogRepository.findByIdForUpdate(requestedId).orElseGet(SkuCatalog::new);
        boolean created = sku.getSkuId() == null;
        if (created) {
            long code = skuCatalogRepository.nextSkuCode();
            String skuId = requestedId.isEmpty() ? "SKU-" + code : requestedId;
            if (skuCatalogRepository.existsById(skuId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_EXISTS);
            }
            sku.setSkuId(skuId);
            sku.setSkuCode(code);
        } else if (sku.getSkuCode() == null) {
            sku.setSkuCode(skuCatalogRepository.nextSkuCode());
        }
        String barcode = trimToNull(skuReq.barcode());
        if (skuCatalogRepository.existsByBarcode(barcode, sku.getSkuId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_BARCODE_EXISTS);
        }
        if (skuCatalogRepository.existsBySkuName(skuReq.skuName(), sku.getSkuId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, ApiMessages.SKU_NAME_EXISTS);
        }
        String oldImageUrl = sku.getImageUrl();
        applySkuFields(sku, skuReq, request);
        touchSkuUpdater(sku, operatorId);
        skuCatalogRepository.save(sku);
        String newImageUrl = trimToNull(skuReq.imageUrl());
        if (oldImageUrl != null && !oldImageUrl.equals(newImageUrl)) {
            fileAttachmentService.releaseSkuImageIfUnused(oldImageUrl);
        }

        String skuId = sku.getSkuId();
        String className = resolveClassName(request.yoloClassName(), sku);
        SkuVisionMapping mapping = yoloRepository.findByIdForUpdate(className).orElse(new SkuVisionMapping());
        mapping.setClassName(className);
        mapping.setSkuId(skuId);
        mapping.setMinConfidence(request.detectionMinConfidence());
        mapping.setMappingSource(request.mappingSource());
        yoloRepository.save(mapping);

        auditService.appendLog(operatorId, created ? "SKU_VISION_ENROLL_CREATE" : "SKU_VISION_ENROLL_UPDATE",
                "SKU", skuId, "class=" + className + " status=" + sku.getVisionEnrollmentStatus());
        return sku.toDto();
    }

    @Transactional
    public SkuCatalogDto updateEnrollmentStatus(Long operatorId, String skuId, String status) {
        permissionService.requirePermission(operatorId, PERM_OPS_VISION_EDIT);
        return runWithSkuVisionLock(skuId, () -> doUpdateEnrollmentStatus(operatorId, skuId, status));
    }

    private SkuCatalogDto doUpdateEnrollmentStatus(Long operatorId, String skuId, String status) {
        SkuCatalog sku = requireSkuForUpdate(skuId);
        String next = normalizeStatus(status);
        assertAllowedStatus(next);
        if (PRODUCTION.equals(next) || TESTED.equals(next) || MAPPING.equals(next)) {
            requireMappedClass(sku);
        }
        sku.setVisionEnrollmentStatus(next);
        touchSkuUpdater(sku, operatorId);
        skuCatalogRepository.save(sku);
        auditService.appendLog(operatorId, "SKU_VISION_STATUS", "SKU", skuId,
                next + " pipeline=" + MODEL_PIPELINE_WAITING);
        return sku.toDto();
    }

    /** 按固定顺序推进：DRAFT→MAPPING→TESTED→PRODUCTION。 */
    @Transactional
    public SkuVisionEnrollmentRowDto advanceEnrollment(Long operatorId, String skuId) {
        permissionService.requirePermission(operatorId, PERM_OPS_VISION_EDIT);
        return runWithSkuVisionLock(skuId, () -> doAdvanceEnrollment(operatorId, skuId));
    }

    private SkuVisionEnrollmentRowDto doAdvanceEnrollment(Long operatorId, String skuId) {
        SkuCatalog sku = requireSkuForUpdate(skuId);
        String current = normalizeStatus(sku.getVisionEnrollmentStatus());
        String next = nextStatus(current);
        if (next == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "已处于生产状态，无需再推进（模型管线仍为 " + MODEL_PIPELINE_WAITING + "）");
        }
        if (MAPPING.equals(next) || TESTED.equals(next) || PRODUCTION.equals(next)) {
            requireMappedClass(sku);
        }
        sku.setVisionEnrollmentStatus(next);
        touchSkuUpdater(sku, operatorId);
        skuCatalogRepository.save(sku);
        auditService.appendLog(operatorId, "SKU_VISION_ADVANCE", "SKU", skuId,
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

    /** 结算前校验：识别 SKU 须在柜内库存、enrollment=PRODUCTION，且置信度达 SKU 扣款阈值。 */
    @Transactional(readOnly = true)
    public Optional<String> validateSettlementItems(String deviceId,
                                                    List<VisionServiceClient.RecognizedItem> items) {
        if (items == null || items.isEmpty()) {
            return Optional.empty();
        }
        if (stagingProperties.stagingMode() || stagingProperties.gravityFallbackSettle()) {
            return Optional.empty();
        }
        DeviceVisionContextDto ctx = self.deviceVisionContext(deviceId);
        Map<String, SkuVisionContextItemDto> allowed = ctx.skus().stream()
                .collect(Collectors.toMap(SkuVisionContextItemDto::skuId, s -> s, (a, b) -> a));
        Map<String, SkuCatalog> skuById = skuCatalogRepository.findAllById(
                        items.stream().map(VisionServiceClient.RecognizedItem::skuId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(SkuCatalog::getSkuId, s -> s, (a, b) -> a));
        for (VisionServiceClient.RecognizedItem item : items) {
            SkuVisionContextItemDto row = allowed.get(item.skuId());
            if (row == null) {
                return Optional.of("识别 SKU " + item.skuId() + " 不在柜机 " + deviceId + " 在售白名单");
            }
            if (!PRODUCTION.equalsIgnoreCase(row.visionEnrollmentStatus())) {
                return Optional.of("SKU " + item.skuId() + " 识别入驻状态为 "
                        + row.visionEnrollmentStatus() + "，未进结算白名单，不可自动扣款");
            }
            SkuCatalog sku = skuById.get(item.skuId());
            if (sku != null) {
                float minCharge = sku.getMinChargeConfidence();
                if (item.confidence() < minCharge) {
                    return Optional.of("SKU " + item.skuId() + " 置信度 "
                            + item.confidence() + " 低于扣款阈值 " + minCharge);
                }
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
        String slug = collapseSlugUnderscores(
                skuName.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\u4e00-\\u9fff]+", "_"));
        while (slug.startsWith("_")) {
            slug = slug.substring(1);
        }
        while (slug.endsWith("_")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        if (slug.isBlank()) {
            return "sku_unknown";
        }
        if (slug.length() > 48) {
            slug = slug.substring(0, 48);
        }
        return slug;
    }

    /** 合并连续下划线，避免 ReDoS 敏感的正则 `_+`。 */
    private static String collapseSlugUnderscores(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(raw.length());
        boolean prevUnderscore = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '_') {
                if (!prevUnderscore) {
                    out.append('_');
                }
                prevUnderscore = true;
            } else {
                out.append(c);
                prevUnderscore = false;
            }
        }
        return out.toString();
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
        applyBasicSkuFields(sku, skuReq);
        applyShelfAndCostFields(sku, skuReq);
        applyVisionFields(sku, skuReq, request);
    }

    private void applyBasicSkuFields(SkuCatalog sku, UpsertSkuRequest skuReq) {
        sku.setSkuName(skuReq.skuName().trim());
        sku.setPriceCents(skuReq.priceCents());
        sku.setWeightGrams(skuReq.weightGrams());
        sku.setVisionEnabled(skuReq.visionEnabled());
        sku.setImageUrl(trimToNull(skuReq.imageUrl()));
        sku.setDescription(trimToNull(skuReq.description()));
        sku.setCategory(trimToNull(skuReq.category()));
        sku.setBarcode(trimToNull(skuReq.barcode()));
        sku.setBrand(trimToNull(skuReq.brand()));
        sku.setSpec(trimToNull(skuReq.spec()));
        sku.setUnit(skuReq.unit() != null && !skuReq.unit().isBlank() ? skuReq.unit().trim() : "件");
        sku.setStatus(skuReq.status());
    }

    private void applyShelfAndCostFields(SkuCatalog sku, UpsertSkuRequest skuReq) {
        sku.setShelfLifeDays(skuReq.shelfLifeDays());
        sku.setNearExpiryDays(skuReq.nearExpiryDays());
        sku.setBlockSaleDaysBeforeExpiry(skuReq.blockSaleDaysBeforeExpiry());
        sku.setStorageType(skuReq.storageType());
        sku.setPurchaseCostCents(skuReq.purchaseCostCents());
        sku.setNearExpiryPriceCents(skuReq.nearExpiryPriceCents());
        if (skuReq.minChargeConfidence() != null) {
            sku.setMinChargeConfidence(skuReq.minChargeConfidence());
        }
    }

    private void applyVisionFields(SkuCatalog sku, UpsertSkuRequest skuReq, UpsertSkuVisionEnrollmentRequest request) {
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

    private SkuCatalog requireSkuForUpdate(String skuId) {
        return skuCatalogRepository.findByIdForUpdate(skuId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SKU_NOT_FOUND));
    }

    static String skuVisionLockKey(String skuId) {
        return "sku:vision:" + skuId.trim();
    }

    private <T> T runWithSkuVisionLock(String skuId, java.util.function.Supplier<T> action) {
        String key = skuVisionLockKey(skuId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU 识别入驻处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private static String normalizeStatus(String status) {
        return status == null ? CabinetConstants.PROMOTION_STATUS_DRAFT : status.trim().toUpperCase(Locale.ROOT);
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
            return MAPPING;
        }
        if (idx >= STATUS_ORDER.size() - 1) {
            return null;
        }
        return STATUS_ORDER.get(idx + 1);
    }

    private void requireMappedClass(SkuCatalog sku) {
        if (sku.getYoloClassName() == null || sku.getYoloClassName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "请先保存端侧识别类名映射后再推进状态");
        }
        if (!yoloRepository.existsById(sku.getYoloClassName().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "识别类名尚未写入映射表，请先保存「识别入驻」");
        }
    }

    private SkuVisionEnrollmentRowDto toEnrollmentRow(SkuCatalog sku) {
        String status = normalizeStatus(sku.getVisionEnrollmentStatus());
        // 结算白名单：生产态 + 已绑定识别类名（算法无关；库字段历史名为 yoloClassName）
        boolean mappingEffective = PRODUCTION.equals(status)
                && sku.getYoloClassName() != null
                && !sku.getYoloClassName().isBlank();
        String next = nextStatus(status);
        String nextAction = switch (status) {
            case CabinetConstants.PROMOTION_STATUS_DRAFT -> "保存识别类名映射并推进到「映射中」";
            case MAPPING -> "完成端侧/联调抽检后推进到「已测试」";
            case TESTED -> "确认转生产（进入结算白名单；端侧 mock/低置信仍进争议）";
            case PRODUCTION -> "已在结算白名单；自动扣款取决于端侧识别质量";
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

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record CatalogClassRow(String skuId, String className, String skuName) {}
}
