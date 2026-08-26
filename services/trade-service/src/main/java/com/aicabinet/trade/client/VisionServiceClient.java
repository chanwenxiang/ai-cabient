package com.aicabinet.trade.client;

import com.aicabinet.common.constants.InternalApiConstants;
import com.aicabinet.trade.config.VisionApiProperties;
import com.aicabinet.trade.domain.ShoppingSession;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class VisionServiceClient {
    private static final String UPLOAD_JPG = "upload.jpg";


    private static final Logger log = LoggerFactory.getLogger(VisionServiceClient.class);
    private static final TypeReference<List<Map<String, Object>>> CLIP_LIST_TYPE = new TypeReference<>() {};

    private final RestClient restClient;
    private final VisionApiProperties visionApiProperties;
    private final ObjectMapper objectMapper;

    public VisionServiceClient(@Value("${aicabinet.vision-service.url:http://localhost:8082}") String baseUrl,
                               VisionApiProperties visionApiProperties,
                               ObjectMapper objectMapper) {
        this.restClient = InternalRestClientFactory.create(baseUrl);
        this.visionApiProperties = visionApiProperties;
        this.objectMapper = objectMapper;
    }

    @CircuitBreaker(name = "visionService", fallbackMethod = "fallbackRecognizeSession")
    public RecognitionResult recognize(ShoppingSession session) {
        String fusionMode = session.getCameraFusionMode();
        String clipsJson = session.getVideoClips();
        if ("MULTI".equalsIgnoreCase(fusionMode) && clipsJson != null && !clipsJson.isBlank()) {
            return recognizeMulti(session.getSessionId(), session.getVideoUri(), clipsJson, fusionMode, null);
        }
        return recognize(session.getSessionId(), session.getVideoUri(), session.getDeviceId(), null);
    }

    @CircuitBreaker(name = "visionService", fallbackMethod = "fallbackRecognizeSnapshot")
    public RecognitionResult recognizeInventorySnapshot(ShoppingSession session) {
        String fusionMode = session.getCameraFusionMode();
        String clipsJson = session.getVideoClips();
        String mode = "INVENTORY_SNAPSHOT";
        if ("MULTI".equalsIgnoreCase(fusionMode) && clipsJson != null && !clipsJson.isBlank()) {
            return recognizeMulti(session.getSessionId(), session.getVideoUri(), clipsJson, fusionMode, mode);
        }
        return recognize(session.getSessionId(), session.getVideoUri(), session.getDeviceId(), mode);
    }

    public RecognitionResult recognize(String sessionId, String videoUri) {
        return recognize(sessionId, videoUri, null, null);
    }

    public RecognitionResult recognize(String sessionId, String videoUri, String deviceId) {
        return recognize(sessionId, videoUri, deviceId, null);
    }

    public RecognitionResult recognize(String sessionId, String videoUri, String deviceId, String recognitionMode) {
        log.info("request vision recognize session={} mode={}", sessionId, recognitionMode);
        VisionRecognizeResponse body = restClient.post()
                .uri("/api/v2/vision/recognize")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(InternalApiConstants.API_KEY_HEADER, visionApiProperties.key())
                .body(new RecognizeRequest(sessionId, videoUri != null ? videoUri : "", null, null, deviceId, recognitionMode))
                .retrieve()
                .body(VisionRecognizeResponse.class);

        return toRecognitionResult(sessionId, body);
    }

    private RecognitionResult recognizeMulti(String sessionId, String videoUri, String clipsJson, String fusionMode,
                                             String recognitionMode) {
        log.info("request vision multi-camera session={} fusion={}", sessionId, fusionMode);
        try {
            List<Map<String, Object>> clips = objectMapper.readValue(clipsJson, CLIP_LIST_TYPE);
            VisionRecognizeResponse body = restClient.post()
                    .uri("/api/v2/vision/recognize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(InternalApiConstants.API_KEY_HEADER, visionApiProperties.key())
                    .body(new RecognizeRequest(sessionId, videoUri, clips, fusionMode, null, recognitionMode))
                    .retrieve()
                    .body(VisionRecognizeResponse.class);
            return toRecognitionResult(sessionId, body);
        } catch (Exception e) {
            log.warn("multi-camera parse failed session={}, fallback single uri", sessionId, e);
            return recognize(sessionId, videoUri, null, recognitionMode);
        }
    }

    public RecognitionResult recognizeUpload(String sessionId, byte[] data, String filename) {
        return recognizeUpload(sessionId, data, filename, null);
    }

    public RecognitionResult recognizeUpload(String sessionId, byte[] data, String filename, String deviceId) {
        log.info("request vision upload recognize session={} file={} device={}", sessionId, filename, deviceId);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("session_id", sessionId);
        if (deviceId != null && !deviceId.isBlank()) {
            body.add("device_id", deviceId.trim());
        }
        body.add("file", new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename != null && !filename.isBlank() ? filename : UPLOAD_JPG;
            }
        });

        VisionRecognizeResponse response = restClient.post()
                .uri("/api/v2/vision/recognize/upload")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .header(InternalApiConstants.API_KEY_HEADER, visionApiProperties.key())
                .body(body)
                .retrieve()
                .body(VisionRecognizeResponse.class);

        return toRecognitionResult(sessionId, response);
    }

    public Map<String, Object> suggestClassFromImage(byte[] data, String filename, String skuName) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename != null && !filename.isBlank() ? filename : UPLOAD_JPG;
            }
        });
        if (skuName != null && !skuName.isBlank()) {
            body.add("sku_name", skuName);
        }
        Map<String, Object> result = restClient.post()
                .uri("/api/v2/vision/suggest-class")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .header(InternalApiConstants.API_KEY_HEADER, visionApiProperties.key())
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        return result != null ? result : Map.of();
    }

    public RecognitionResult suggestDisputeSkus(String deviceId, byte[] data, String filename) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("device_id", deviceId != null ? deviceId : "");
        body.add("file", new ByteArrayResource(data) {
            @Override
            public String getFilename() {
                return filename != null && !filename.isBlank() ? filename : UPLOAD_JPG;
            }
        });
        VisionRecognizeResponse response = restClient.post()
                .uri("/api/v2/vision/dispute-suggest")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .header(InternalApiConstants.API_KEY_HEADER, visionApiProperties.key())
                .body(body)
                .retrieve()
                .body(VisionRecognizeResponse.class);
        return toRecognitionResult("dispute-suggest", response);
    }

    private RecognitionResult toRecognitionResult(String sessionId, VisionRecognizeResponse body) {
        if (body == null) {
            throw new IllegalStateException("empty vision response");
        }

        boolean needReview = body.needReview() != null && body.needReview();
        List<VisionItemResponse> items = body.items() != null ? body.items() : List.of();
        log.info("vision result session={} needReview={} items={}", sessionId, needReview, items.size());

        return new RecognitionResult(
                body.taskId(),
                items.stream()
                        .map(i -> new RecognizedItem(
                                i.skuId(),
                                i.quantity() != null ? i.quantity() : 0,
                                i.confidence() != null ? i.confidence() : 0f))
                        .toList(),
                body.overallConfidence() != null ? body.overallConfidence() : 0f,
                needReview,
                body.modelVersion(),
                body.detectedClasses() != null ? body.detectedClasses() : List.of()
        );
    }

    record RecognizeRequest(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("video_uri") String videoUri,
            @JsonProperty("video_clips") List<Map<String, Object>> videoClips,
            @JsonProperty("camera_fusion_mode") String cameraFusionMode,
            @JsonProperty("device_id") String deviceId,
            @JsonProperty("recognition_mode") String recognitionMode
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VisionRecognizeResponse(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("items") List<VisionItemResponse> items,
            @JsonProperty("overall_confidence") Float overallConfidence,
            @JsonProperty("need_review") Boolean needReview,
            @JsonProperty("model_version") String modelVersion,
            @JsonProperty("detected_classes") List<String> detectedClasses
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VisionItemResponse(
            @JsonProperty("sku_id") String skuId,
            @JsonProperty("quantity") Integer quantity,
            @JsonProperty("confidence") Float confidence
    ) {}

    public record RecognizedItem(String skuId, int quantity, float confidence) {}

    public record RecognitionResult(
            String taskId,
            List<RecognizedItem> items,
            float overallConfidence,
            boolean needReview,
            String modelVersion,
            List<String> detectedClasses
    ) {}
    /** CircuitBreaker fallback: vision-service 不可用时返回 needReview */
    public RecognitionResult fallbackRecognizeSession(ShoppingSession session, Throwable t) {
        log.warn("vision circuit breaker OPEN for session={}, fallback to needReview: {}", session.getSessionId(), t.getMessage());
        return new RecognitionResult("CIRCUIT-OPEN-" + session.getSessionId(), List.of(), 0f, true, "circuit-breaker", List.of("fallback"));
    }

    public RecognitionResult fallbackRecognizeSnapshot(ShoppingSession session, Throwable t) {
        log.warn("vision circuit breaker OPEN for snapshot session={}, fallback: {}", session.getSessionId(), t.getMessage());
        return new RecognitionResult("CIRCUIT-OPEN-" + session.getSessionId(), List.of(), 0f, true, "circuit-breaker", List.of("fallback"));
    }
}
