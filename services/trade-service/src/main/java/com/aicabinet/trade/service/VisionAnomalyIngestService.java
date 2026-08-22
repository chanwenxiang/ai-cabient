package com.aicabinet.trade.service;

import com.aicabinet.common.dto.OpsExceptionDto;
import com.aicabinet.common.dto.VisionAnomalyEventDto;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 端侧视觉异常事件接入：错拿 / 遮挡 / 防撬 / 异常开门 → 运营异常中心（VISION_ANOMALY），
 * 高危事件（防撬、异常开门）同步推送钉钉 / 企业微信 / 通用 Webhook。
 */
@Service
public class VisionAnomalyIngestService {

    private final OpsExceptionService opsExceptionService;
    private final OpsAlertDispatcher opsAlertDispatcher;
    private final DistributedLockService distributedLockService;

    public VisionAnomalyIngestService(OpsExceptionService opsExceptionService,
                                      OpsAlertDispatcher opsAlertDispatcher,
                                      DistributedLockService distributedLockService) {
        this.opsExceptionService = opsExceptionService;
        this.opsAlertDispatcher = opsAlertDispatcher;
        this.distributedLockService = distributedLockService;
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
            OpsExceptionDto created = runWithDeviceLock(event.deviceId().trim(),
                    () -> ingestSingle(event));
            out.add(created);
        }
        return out;
    }

    private OpsExceptionDto ingestSingle(VisionAnomalyEventDto event) {
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
        return created;
    }

    static String visionAnomalyDeviceLockKey(String deviceId) {
        return "device:vision-anomaly:" + deviceId;
    }

    private <T> T runWithDeviceLock(String deviceId, Supplier<T> action) {
        String lockKey = visionAnomalyDeviceLockKey(deviceId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "视觉异常处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(lockKey);
        }
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
