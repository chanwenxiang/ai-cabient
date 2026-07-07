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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class VisionServiceClient {

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

    public RecognitionResult recognize(ShoppingSession session) {
        String fusionMode = session.getCameraFusionMode();
        String clipsJson = session.getVideoClips();
        if ("MULTI".equalsIgnoreCase(fusionMode) && clipsJson != null && !clipsJson.isBlank()) {
            return recognizeMulti(session.getSessionId(), session.getVideoUri(), clipsJson, fusionMode);
        }
        return recognize(session.getSessionId(), session.getVideoUri());
    }

    public RecognitionResult recognize(String sessionId, String videoUri) {
        log.info("request vision recognize session={}", sessionId);
        VisionRecognizeResponse body = restClient.post()
                .uri("/api/v2/vision/recognize")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .header(InternalApiConstants.API_KEY_HEADER, visionApiProperties.key())
                .body(new RecognizeRequest(sessionId, videoUri != null ? videoUri : "", null, null))
                .retrieve()
                .body(VisionRecognizeResponse.class);

        return toRecognitionResult(sessionId, body);
    }

    private RecognitionResult recognizeMulti(String sessionId, String videoUri, String clipsJson, String fusionMode) {
        log.info("request vision multi-camera session={} fusion={}", sessionId, fusionMode);
        try {
            List<Map<String, Object>> clips = objectMapper.readValue(clipsJson, CLIP_LIST_TYPE);
            VisionRecognizeResponse body = restClient.post()
                    .uri("/api/v2/vision/recognize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(InternalApiConstants.API_KEY_HEADER, visionApiProperties.key())
                    .body(new RecognizeRequest(sessionId, videoUri, clips, fusionMode))
                    .retrieve()
                    .body(VisionRecognizeResponse.class);
            return toRecognitionResult(sessionId, body);
        } catch (Exception e) {
            log.warn("multi-camera parse failed session={}, fallback single uri", sessionId, e);
            return recognize(sessionId, videoUri);
        }
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
                needReview
        );
    }

    record RecognizeRequest(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("video_uri") String videoUri,
            @JsonProperty("video_clips") List<Map<String, Object>> videoClips,
            @JsonProperty("camera_fusion_mode") String cameraFusionMode
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record VisionRecognizeResponse(
            @JsonProperty("task_id") String taskId,
            @JsonProperty("items") List<VisionItemResponse> items,
            @JsonProperty("overall_confidence") Float overallConfidence,
            @JsonProperty("need_review") Boolean needReview
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
            boolean needReview
    ) {}
}
