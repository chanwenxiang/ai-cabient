package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateSessionRequest;
import com.aicabinet.common.dto.DevRecognitionItemDto;
import com.aicabinet.common.dto.DevRecognitionPreviewDto;
import com.aicabinet.common.dto.DevRecognitionTestRequest;
import com.aicabinet.common.dto.DevRecognitionTestResponse;
import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.SessionDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.repository.ShoppingSessionRepository;
import com.aicabinet.trade.repository.SkuCatalogRepository;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 上传商品图片，走 vision-service 真实 YOLO 识别（运营测试用，不注入写死 SKU）。
 */
@Service
public class RecognitionTestService {

    private static final Logger log = LoggerFactory.getLogger(RecognitionTestService.class);

    private final SessionService sessionService;
    private final ShoppingSessionRepository sessionRepository;
    private final SettlementService settlementService;
    private final VisionServiceClient visionClient;
    private final SkuCatalogRepository skuCatalogRepository;

    public RecognitionTestService(SessionService sessionService,
                                   ShoppingSessionRepository sessionRepository,
                                   SettlementService settlementService,
                                   VisionServiceClient visionClient,
                                   SkuCatalogRepository skuCatalogRepository) {
        this.sessionService = sessionService;
        this.sessionRepository = sessionRepository;
        this.settlementService = settlementService;
        this.visionClient = visionClient;
        this.skuCatalogRepository = skuCatalogRepository;
    }

    @Transactional(readOnly = true)
    public DevRecognitionPreviewDto previewUpload(byte[] imageBytes, String filename) {
        return previewUpload(imageBytes, filename, null);
    }

    @Transactional(readOnly = true)
    public DevRecognitionPreviewDto previewUpload(byte[] imageBytes, String filename, String deviceId) {
        String probeId = "PREVIEW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        VisionServiceClient.RecognitionResult recognition = visionClient.recognizeUpload(
                probeId, imageBytes, filename, deviceId);
        return toPreview(recognition);
    }

    @Transactional(readOnly = true)
    public DevRecognitionPreviewDto suggestDisputeSkus(String deviceId, byte[] imageBytes, String filename) {
        if (deviceId == null || deviceId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定 deviceId");
        }
        VisionServiceClient.RecognitionResult recognition = visionClient.suggestDisputeSkus(
                deviceId.trim(), imageBytes, filename != null ? filename : "dispute.jpg");
        DevRecognitionPreviewDto preview = toPreview(recognition);
        String hint = preview.hint() != null ? preview.hint() : "";
        if (recognition.needReview()) {
            hint = "DeepSeek 建议（需人工确认）：" + hint;
        }
        return new DevRecognitionPreviewDto(
                preview.items(),
                preview.detectedClasses(),
                preview.overallConfidence(),
                true,
                preview.modelVersion(),
                hint
        );
    }

    @Transactional
    public DevRecognitionTestResponse runWithUpload(Long userId,
                                                    DevRecognitionTestRequest request,
                                                    byte[] imageBytes,
                                                    String filename,
                                                    boolean settle) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请上传商品图片");
        }
        String deviceId = request.deviceId().trim();
        String safeName = filename != null && !filename.isBlank() ? filename : "upload.jpg";

        String probeId = "PREVIEW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        VisionServiceClient.RecognitionResult recognition = visionClient.recognizeUpload(
                probeId, imageBytes, safeName);
        DevRecognitionPreviewDto preview = toPreview(recognition);

        if (!settle) {
            return new DevRecognitionTestResponse(null, null, null, preview.hint(), preview);
        }

        assertRecognitionSettleable(preview);

        SessionDto sessionDto = switch (normalizeMode(request.mode())) {
            case "CLOSE_ONLY" -> prepareExistingSession(userId, request.sessionId(), deviceId);
            case "FULL" -> sessionService.createSessionForDevTest(userId, new CreateSessionRequest(deviceId, null));
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.INVALID_REQUEST);
        };

        ShoppingSession session = sessionRepository.findById(sessionDto.sessionId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        session.setCloseTime(Instant.now());
        session.setVideoUri("upload://" + safeName);
        session.setUploadStatus("UPLOADED");
        sessionRepository.save(session);

        sessionDto = sessionService.completeDevUploadRecognition(session.getSessionId(), recognition);
        OrderDto order = tryLoadOrder(sessionDto);
        String hint = buildHint(sessionDto, order, preview);
        log.info("ops upload recognition session={} state={} items={}",
                sessionDto.sessionId(), sessionDto.state(), preview.items().size());
        return new DevRecognitionTestResponse(sessionDto, order, session.getVideoUri(), hint, preview);
    }

    /** 识别测试结算前校验：模型不可用或未识别到 SKU 时不创建会话、不进申诉。 */
    private static void assertRecognitionSettleable(DevRecognitionPreviewDto preview) {
        String modelVersion = preview.modelVersion() != null ? preview.modelVersion() : "";
        if ("yolov8-unavailable".equalsIgnoreCase(modelVersion)
                || "mock-v1".equalsIgnoreCase(modelVersion)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, preview.hint());
        }
        if (preview.items() == null || preview.items().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, preview.hint());
        }
        if (preview.needReview()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "识别置信度偏低或未完全匹配 SKU，识别测试不支持直接结算。"
                            + "请先用「仅预览识别」确认结果，或配置 YOLO→SKU 映射后重试。");
        }
    }

    private SessionDto prepareExistingSession(Long userId, String sessionId, String deviceId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CLOSE_ONLY 模式需要 sessionId");
        }
        ShoppingSession session = sessionRepository.findById(sessionId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        if (!session.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.ACCESS_DENIED);
        }
        if (!session.getDeviceId().equals(deviceId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DEVICE_MISMATCH);
        }
        return sessionService.ensureShoppingForDevTest(session.getSessionId());
    }

    private DevRecognitionPreviewDto toPreview(VisionServiceClient.RecognitionResult recognition) {
        Map<String, String> skuNames = loadSkuNames(recognition.items().stream()
                .map(VisionServiceClient.RecognizedItem::skuId)
                .toList());

        List<DevRecognitionItemDto> items = recognition.items().stream()
                .map(i -> {
                    SkuCatalog sku = skuCatalogRepository.findById(i.skuId()).orElse(null);
                    int unit = sku != null ? sku.getPriceCents() : 0;
                    return new DevRecognitionItemDto(
                            i.skuId(),
                            skuNames.getOrDefault(i.skuId(), i.skuId()),
                            i.quantity(),
                            i.confidence(),
                            unit > 0 ? unit : null,
                            unit > 0 ? unit * i.quantity() : null);
                })
                .toList();

        String hint = buildPreviewHint(recognition, items);
        return new DevRecognitionPreviewDto(
                items,
                recognition.detectedClasses() != null ? recognition.detectedClasses() : List.of(),
                recognition.overallConfidence(),
                recognition.needReview(),
                recognition.modelVersion() != null ? recognition.modelVersion() : "unknown",
                hint
        );
    }

    private Map<String, String> loadSkuNames(List<String> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return skuCatalogRepository.findAllById(skuIds).stream()
                .collect(Collectors.toMap(SkuCatalog::getSkuId, SkuCatalog::getSkuName));
    }

    private static String buildPreviewHint(VisionServiceClient.RecognitionResult recognition,
                                           List<DevRecognitionItemDto> items) {
        if (!items.isEmpty()) {
            String lines = items.stream()
                    .map(i -> {
                        String base = i.skuName() + " x" + i.quantity()
                                + "（置信度 " + Math.round(i.confidence() * 100) + "%）";
                        if (i.lineAmountCents() != null && i.lineAmountCents() > 0) {
                            return base + " ¥" + String.format("%.2f", i.lineAmountCents() / 100.0);
                        }
                        return base;
                    })
                    .collect(Collectors.joining("、"));
            if (recognition.needReview()) {
                return "识别到：" + lines + "；置信度偏低，建议人工核对。";
            }
            return "识别到：" + lines;
        }
        List<String> detected = recognition.detectedClasses() != null
                ? recognition.detectedClasses()
                : List.of();
        if (!detected.isEmpty()) {
            return "未匹配到商品 SKU，YOLO 检测到：" + String.join("、", detected)
                    + "。请在管理后台配置 YOLO→SKU 映射，或换一张已录入商品图。";
        }
        if ("yolov8-unavailable".equalsIgnoreCase(recognition.modelVersion())
                || "mock-v1".equalsIgnoreCase(recognition.modelVersion())) {
            return "识别服务暂不可用，请稍后再试。";
        }
        return "未识别到商品，请换一张清晰的单品照片。";
    }

    private OrderDto tryLoadOrder(SessionDto session) {
        if (session.state() != SessionState.COMPLETED) {
            return null;
        }
        try {
            return settlementService.getOrderBySession(session.sessionId());
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    private static String buildHint(SessionDto session, OrderDto order, DevRecognitionPreviewDto preview) {
        if (session.state() == SessionState.COMPLETED && order != null) {
            return "识别成功，已生成订单。" + preview.hint();
        }
        return switch (session.state()) {
            case COMPLETED -> "会话已完成但未生成订单。" + preview.hint();
            case DISPUTED -> "识别结果需人工审核。" + preview.hint();
            case FAILED -> "结算失败：" + (session.failReason() != null ? session.failReason() : preview.hint());
            default -> preview.hint();
        };
    }

    private static String normalizeMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "FULL";
        }
        return mode.trim().toUpperCase();
    }
}
