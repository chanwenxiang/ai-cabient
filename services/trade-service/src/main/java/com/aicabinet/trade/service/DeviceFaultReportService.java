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
    private static final String DOOR_CLOSE = "DOOR_CLOSE";
    private static final String DOOR_OPEN = "DOOR_OPEN";
    private static final String OTHER = "OTHER";




    private static final Logger log = LoggerFactory.getLogger(DeviceFaultReportService.class);



    /** 历史/脚本别名 → 字典 canonical 值 */

    private static final Map<String, String> LEGACY_ISSUE_ALIASES = Map.of(

            "DOOR_WONT_OPEN", DOOR_OPEN,

            "DOOR_WONT_CLOSE", DOOR_CLOSE,

            "DOOR", DOOR_OPEN

    );



    private final DeviceFaultReportMapper repository;

    private final DeviceValidationService deviceValidationService;

    private final OpsExceptionService opsExceptionService;

    private final DistributedLockService distributedLockService;

    private final SysDictService sysDictService;



    public DeviceFaultReportService(DeviceFaultReportMapper repository,

                                    DeviceValidationService deviceValidationService,

                                    OpsExceptionService opsExceptionService,

                                    DistributedLockService distributedLockService,

                                    SysDictService sysDictService) {

        this.repository = repository;

        this.deviceValidationService = deviceValidationService;

        this.opsExceptionService = opsExceptionService;

        this.distributedLockService = distributedLockService;

        this.sysDictService = sysDictService;

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

                "DEVICE_FAULT", "HIGH", new OpsExceptionService.ExceptionReport.ExceptionRefs(id, null, null, userId), "消费者设备报修", formatFaultDetail(row.getIssueType(), row.getDescription())

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



    private String normalizeIssue(String raw) {

        if (raw == null || raw.isBlank()) {

            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "issueType required");

        }

        String value = raw.trim().toUpperCase();

        if (sysDictService.isActiveDictValue(SysDictService.DEVICE_FAULT_ISSUE, value)) {

            return value;

        }

        String alias = LEGACY_ISSUE_ALIASES.get(value);

        if (alias != null && sysDictService.isActiveDictValue(SysDictService.DEVICE_FAULT_ISSUE, alias)) {

            return alias;

        }

        if (sysDictService.isActiveDictValue(SysDictService.DEVICE_FAULT_ISSUE, OTHER)) {

            return OTHER;

        }

        return switch (value) {

            case DOOR_OPEN, DOOR_CLOSE, "PRODUCT", "PAYMENT", OTHER -> value;

            default -> OTHER;

        };

    }



    private static String trimDesc(String raw) {

        if (raw == null) return null;

        String t = raw.trim();

        return t.isEmpty() ? null : t.substring(0, Math.min(512, t.length()));

    }



    private String issueTypeLabel(String code) {

        if (code == null || code.isBlank()) {

            return "其他";

        }

        String key = code.trim().toUpperCase();

        String canonical = LEGACY_ISSUE_ALIASES.getOrDefault(key, key);

        return sysDictService.labelOf(SysDictService.DEVICE_FAULT_ISSUE, canonical, fallbackIssueLabel(canonical));

    }



    static String fallbackIssueLabel(String code) {

        if (code == null || code.isBlank()) {

            return "其他";

        }

        return switch (code.trim().toUpperCase()) {

            case DOOR_OPEN, "DOOR_WONT_OPEN" -> "打不开门";

            case DOOR_CLOSE, "DOOR_WONT_CLOSE" -> "门关不上";

            case "PRODUCT" -> "商品异常";

            case "PAYMENT" -> "扣款问题";

            case OTHER -> "其他";

            default -> "其他";

        };

    }



    private String formatFaultDetail(String issueType, String description) {

        String detail = "问题类型=" + issueTypeLabel(issueType);

        if (description != null && !description.isBlank()) {

            detail += "；补充说明=" + description.trim();

        }

        return detail;

    }

}


