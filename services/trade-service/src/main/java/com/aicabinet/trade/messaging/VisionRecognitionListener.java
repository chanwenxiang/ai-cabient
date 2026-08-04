package com.aicabinet.trade.messaging;

import com.aicabinet.common.constants.KafkaTopics;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.service.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConditionalOnProperty(prefix = "aicabinet.vision-async", name = "enabled", havingValue = "true")
public class VisionRecognitionListener {

    private static final Logger log = LoggerFactory.getLogger(VisionRecognitionListener.class);

    private final SessionService sessionService;
    private final ObjectMapper objectMapper;

    public VisionRecognitionListener(SessionService sessionService, ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopics.VISION_RECOGNIZE_RESULT, groupId = "trade-service")
    public void onResult(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String sessionId = node.path("sessionId").asText();
            String taskId = node.path("taskId").asText();
            float overall = (float) node.path("overallConfidence").asDouble(0);
            boolean needReview = node.path("needReview").asBoolean(false);

            List<VisionServiceClient.RecognizedItem> items = new ArrayList<>();
            if (node.has("items") && node.get("items").isArray()) {
                for (JsonNode item : node.get("items")) {
                    items.add(new VisionServiceClient.RecognizedItem(
                            item.path("skuId").asText(),
                            item.path("quantity").asInt(1),
                            (float) item.path("confidence").asDouble(0)
                    ));
                }
            }

            VisionServiceClient.RecognitionResult result =
                    new VisionServiceClient.RecognitionResult(taskId, items, overall, needReview, null, List.of());
            sessionService.completeAsyncRecognition(sessionId, result);
            log.info("processed async vision result session={}", sessionId);
        } catch (Exception e) {
            log.error("failed to process vision result payload={}", payload, e);
        }
    }
}
