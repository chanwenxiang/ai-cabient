package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceFaultReportRequest;
import com.aicabinet.trade.domain.DeviceFaultReport;
import com.aicabinet.trade.mapper.DeviceFaultReportMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.function.Supplier;

@Service
public class DeviceFaultReportService {

    private static final Logger log = LoggerFactory.getLogger(DeviceFaultReportService.class);

    private final DeviceFaultReportMapper repository;
    private final DeviceValidationService deviceValidationService;
    private final OpsExceptionService opsExceptionService;
    private final DistributedLockService distributedLockService;

    public DeviceFaultReportService(DeviceFaultReportMapper repository,
                                    DeviceValidationService deviceValidationService,
                                    OpsExceptionService opsExceptionService,
                                    DistributedLockService distributedLockService) {
        this.repository = repository;
        this.deviceValidationService = deviceValidationService;
        this.opsExceptionService = opsExceptionService;
        this.distributedLockService = distributedLockService;
    }

    @Transactional
    public Map<String, String> report(Long userId, String deviceId, DeviceFaultReportRequest request) {
        String id = deviceId.trim().toUpperCase();
        return runWithFaultReportLock(userId, id, () -> doReport(userId, id, request));
    }

    private Map<String, String> doReport(Long userId, String id, DeviceFaultReportRequest request) {
        deviceValidationService.requireDevice(id);
        DeviceFaultReport row = new DeviceFaultReport();
        row.setUserId(userId);
        row.setDeviceId(id);
        row.setIssueType(normalizeIssue(request.issueType()));
        row.setDescription(trimDesc(request.description()));
        repository.save(row);
        opsExceptionService.report(
                "DEVICE_FAULT",
                "HIGH",
                id,
                null,
                null,
                userId,
                "消费者设备报修",
                "问题类型=" + row.getIssueType() + (row.getDescription() == null ? "" : "; " + row.getDescription())
        );
        log.info("device fault reported user={} device={} type={}", userId, id, row.getIssueType());
        return Map.of("reportId", String.valueOf(row.getId()), "message", "报修已提交，我们会尽快处理");
    }

    static String faultReportLockKey(Long userId, String deviceId) {
        return "device:fault-report:" + userId + ":" + deviceId;
    }

    private <T> T runWithFaultReportLock(Long userId, String deviceId, Supplier<T> action) {
        String lockKey = faultReportLockKey(userId, deviceId);
        if (!distributedLockService.tryLock(lockKey, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "报修处理中，请稍后重试");
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

    private static String normalizeIssue(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issueType required");
        }
        return switch (raw.trim().toUpperCase()) {
            case "DOOR_OPEN", "DOOR_CLOSE", "PRODUCT", "PAYMENT", "OTHER" -> raw.trim().toUpperCase();
            default -> "OTHER";
        };
    }

    private static String trimDesc(String raw) {
        if (raw == null) return null;
        String t = raw.trim();
        return t.isEmpty() ? null : t.substring(0, Math.min(512, t.length()));
    }
}
