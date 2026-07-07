package com.aicabinet.trade.messaging;

import com.aicabinet.common.constants.KafkaTopics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(prefix = "aicabinet.vision-async", name = "enabled", havingValue = "true")
public class VisionRecognitionProducer {

    private static final Logger log = LoggerFactory.getLogger(VisionRecognitionProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public VisionRecognitionProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(String sessionId, String videoUri, String taskId) {
        publish(sessionId, videoUri, taskId, null, null);
    }

    public void publish(String sessionId, String videoUri, String taskId,
                        String videoClipsJson, String cameraFusionMode) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("videoUri", videoUri != null ? videoUri : "");
            payload.put("taskId", taskId);
            if (videoClipsJson != null && !videoClipsJson.isBlank()) {
                List<Object> clips = objectMapper.readValue(videoClipsJson, new TypeReference<>() {});
                payload.put("videoClips", clips);
            }
            if (cameraFusionMode != null) {
                payload.put("cameraFusionMode", cameraFusionMode);
            }
            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(KafkaTopics.VISION_RECOGNIZE_REQUEST, sessionId, json);
            log.info("published vision request session={} task={} fusion={}", sessionId, taskId, cameraFusionMode);
        } catch (Exception e) {
            throw new IllegalStateException("failed to publish vision request", e);
        }
    }
}
