package com.aicabinet.trade.messaging;

import com.aicabinet.common.constants.KafkaTopics;
import com.aicabinet.common.dto.VisionRecognitionResultDto;
import com.aicabinet.common.dto.VisionResultIngestResponseDto;
import com.aicabinet.trade.service.VisionResultIngestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 异步视觉识别结果消费者（S-P2-5）。
 * <p><b>审计 A2/P1-8 + 9.4-1（2026-10-15）</b>：本监听器曾是比 HTTP /edge-results
 * 更宽的伪造面——消息里 sessionId 直通结算、{@code modelVersion} 被丢弃（精度闸
 * {@code blocksSilentSettle} 被绕过）、零 deviceId 校验。现改为把 payload 解析成
 * {@link VisionRecognitionResultDto} 后走 {@link VisionResultIngestService#ingest}
 * ——与 HTTP 面完全同一口径（modelVersion 必填、deviceId 会话绑定、状态机与指标），
 * 两入口口径漂移由该服务的既有钉住测试一并看住（铁律 51）。
 * <p>失败策略（H40）：ingest 抛出（400/404/状态冲突等）或解析失败 ⇒ 先同步可靠写入
 * {@link KafkaTopics#VISION_RECOGNIZE_RESULT_DLT}（等待 broker ack），成功后正常返回、
 * offset 随之提交；DLT 写入失败则重抛原异常——不让 offset 提交，交由容器默认错误处理
 * 重试/进容器 DLT，避免消息在「业务失败 + DLT 也失败」时被静默丢弃。
 * 「未采纳」（TOO_EARLY/ALREADY_HANDLED/CANCELLED）是正常业务结论：记日志后放行提交。
 */
@Component
@ConditionalOnProperty(prefix = "aicabinet.vision-async", name = "enabled", havingValue = "true")
public class VisionRecognitionListener {

    private static final Logger log = LoggerFactory.getLogger(VisionRecognitionListener.class);

    private final VisionResultIngestService ingestService;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public VisionRecognitionListener(VisionResultIngestService ingestService,
                                     ObjectMapper objectMapper,
                                     KafkaTemplate<String, String> kafkaTemplate) {
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
    }

    @KafkaListener(topics = KafkaTopics.VISION_RECOGNIZE_RESULT, groupId = "trade-service")
    public void onResult(String payload) {
        try {
            VisionRecognitionResultDto request = parse(payload);
            VisionResultIngestResponseDto response = ingestService.ingest(request);
            if (response.accepted()) {
                log.info("processed async vision result session={} task={}",
                        response.sessionId(), response.taskId());
            } else {
                // 未采纳=正常业务结论（重发/太早/已取消），不进 DLT，offset 正常提交
                log.info("vision result not adopted session={} task={} outcome={} reason={}",
                        response.sessionId(), response.taskId(), response.outcome(), response.reason());
            }
        } catch (Exception e) {
            log.error("failed to process vision result payload={}", payload, e);
            publishToDlt(payload, e);
            // H40：publishToDlt 成功才正常返回（offset 提交）；失败会重抛，容器按默认错误处理兜底
        }
    }

    /** payload → 与 HTTP 面同一 DTO；缺 modelVersion/deviceId 等由 ingest 校验统一拒绝（fail-closed）。 */
    private VisionRecognitionResultDto parse(String payload) throws Exception {
        JsonNode node = objectMapper.readTree(payload);
        List<VisionRecognitionResultDto.Item> items = new ArrayList<>();
        if (node.has("items") && node.get("items").isArray()) {
            for (JsonNode item : node.get("items")) {
                items.add(new VisionRecognitionResultDto.Item(
                        item.path("skuId").asText(),
                        item.path("quantity").asInt(1),
                        (float) item.path("confidence").asDouble(0)
                ));
            }
        }
        List<String> classes = new ArrayList<>();
        if (node.has("detectedClasses") && node.get("detectedClasses").isArray()) {
            node.get("detectedClasses").forEach(c -> classes.add(c.asText()));
        }
        return new VisionRecognitionResultDto(
                node.path("sessionId").asText(null),
                node.path("deviceId").asText(null),
                node.path("taskId").asText(null),
                node.path("traceId").asText(null),
                items,
                node.path("overallConfidence").asDouble(0),
                node.path("needReview").asBoolean(false),
                node.path("modelVersion").asText(null),
                classes,
                node.path("provider").asText(null),
                node.hasNonNull("occurredAt") ? Instant.parse(node.get("occurredAt").asText()) : null
        );
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
