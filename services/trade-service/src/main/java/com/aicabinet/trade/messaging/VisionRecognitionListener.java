package com.aicabinet.trade.messaging;

import com.aicabinet.common.constants.KafkaTopics;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.service.SessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 异步视觉识别结果消费者（S-P2-5）。
 * <p>失败策略（H40）：先同步可靠地写入 {@link KafkaTopics#VISION_RECOGNIZE_RESULT_DLT}
 * （等待 broker ack）；DLT 写入成功后正常返回、offset 随之提交。
 * DLT 写入失败则重抛原异常——不让 offset 提交，交由 Spring Kafka 容器默认错误处理重试/进容器 DLT，
 * 避免消息在「业务失败 + DLT 也失败」时被静默丢弃。
 * 瞬时失败由上游 vision 侧重试/人工回放 DLT，不在此做指数退避重投。
 */
@Component
@ConditionalOnProperty(prefix = "aicabinet.vision-async", name = "enabled", havingValue = "true")
public class VisionRecognitionListener {
    private static final String ITEMS = "items";

    private static final Logger log = LoggerFactory.getLogger(VisionRecognitionListener.class);

    private final SessionService sessionService;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public VisionRecognitionListener(SessionService sessionService,
                                     ObjectMapper objectMapper,
                                     KafkaTemplate<String, String> kafkaTemplate) {
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
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
            if (node.has(ITEMS) && node.get(ITEMS).isArray()) {
                for (JsonNode item : node.get(ITEMS)) {
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
            publishToDlt(payload, e);
            // H40：publishToDlt 成功才正常返回（offset 提交）；失败会重抛，容器按默认错误处理兜底
        }
    }

    private void publishToDlt(String payload, Exception error) {
        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("error", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        envelope.put("payload", payload);
        envelope.put("failedAt", System.currentTimeMillis());
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.hasNonNull("sessionId")) {
                envelope.put("sessionId", node.path("sessionId").asText());
            }
            if (node.hasNonNull("taskId")) {
                envelope.put("taskId", node.path("taskId").asText());
            }
        } catch (Exception ignored) {
            // payload 可能非法 JSON；保留原始字符串即可
        }
        try {
            // H40：同步等待 broker ack，确认消息真正进入 DLT 后才允许提交 offset
            kafkaTemplate.send(KafkaTopics.VISION_RECOGNIZE_RESULT_DLT, envelope.toString())
                    .get(10, TimeUnit.SECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.error("interrupted while publishing vision result DLT payload={}", payload, ie);
            rethrowOriginal(payload, error);
        } catch (Exception dltError) {
            log.error("failed to publish vision result DLT payload={}", payload, dltError);
            rethrowOriginal(payload, error);
        }
    }

    /**
     * H40：DLT 发布失败时重抛原异常（检查型异常包装后抛出），阻止 offset 提交，
     * 交由 Spring Kafka 容器默认错误处理重试/进容器 DLT。
     */
    private void rethrowOriginal(String payload, Exception error) {
        if (error instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw new IllegalStateException(
                "vision result processing failed and DLT publish failed, refusing to commit offset",
                error);
    }
}
