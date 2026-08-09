package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsExceptionDto;
import com.aicabinet.common.dto.VisionAnomalyEventDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 端侧视觉异常事件接入：错拿 / 遮挡 / 防撬 / 异常开门 → 运营异常中心（VISION_ANOMALY），
 * 高危事件（防撬、异常开门）同步推送钉钉 / 企业微信 / 通用 Webhook。
 */
@Service
public class VisionAnomalyIngestService {

    private final OpsExceptionService opsExceptionService;
    private final OpsAlertDispatcher opsAlertDispatcher;

    public VisionAnomalyIngestService(OpsExceptionService opsExceptionService,
                                      OpsAlertDispatcher opsAlertDispatcher) {
        this.opsExceptionService = opsExceptionService;
        this.opsAlertDispatcher = opsAlertDispatcher;
    }

    @Transactional
    public List<OpsExceptionDto> ingest(List<VisionAnomalyEventDto> events) {
        List<OpsExceptionDto> out = new ArrayList<>();
        if (events == null || events.isEmpty()) {
            return out;
        }
        for (VisionAnomalyEventDto event : events) {
            if (event.deviceId() == null || event.deviceId().isBlank()) {
                continue;
            }
            String type = normalizeType(event.eventType());
            String severity = severityFor(type);
            String title = titleFor(type);
            String detail = String.format(
                    "provider=%s confidence=%.2f%s",
                    event.provider() == null || event.provider().isBlank() ? "unknown" : event.provider(),
                    event.confidence(),
                    event.detail() == null || event.detail().isBlank() ? "" : " | " + event.detail());
            OpsExceptionDto created = opsExceptionService.report(
                    "VISION_ANOMALY", severity, event.deviceId(), event.sessionId(),
                    null, null, title, detail);
            if (created != null && ("HIGH".equals(severity) || "CRITICAL".equals(severity))) {
                opsAlertDispatcher.send(
                        "VISION_ANOMALY",
                        "[" + title + "] 柜机 " + event.deviceId(),
                        detail,
                        Map.of(
                                "deviceId", event.deviceId(),
                                "sessionId", event.sessionId() == null ? "" : event.sessionId(),
                                "eventType", type,
                                "provider", event.provider() == null ? "" : event.provider()));
            }
            out.add(created);
        }
        return out;
    }

    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return "ITEM_MISPLACE";
        }
        return type.trim().toUpperCase();
    }

    private static String severityFor(String type) {
        return switch (type) {
            case "TAMPER", "ABNORMAL_OPEN" -> "HIGH";
            default -> "MEDIUM";
        };
    }

    private static String titleFor(String type) {
        return switch (type) {
            case "OCCLUSION" -> "遮挡识别";
            case "TAMPER" -> "防撬告警";
            case "ABNORMAL_OPEN" -> "异常开门";
            default -> "商品错拿";
        };
    }
}
