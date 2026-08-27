package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceTempPlanDto;
import com.aicabinet.common.dto.DeviceTempPlanEntryDto;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.DeviceTempPlan;
import com.aicabinet.trade.domain.DeviceTempPlanEntry;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.DeviceTempPlanEntryMapper;
import com.aicabinet.trade.mapper.DeviceTempPlanMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 温控计划：按当日分钟排程目标温度，调度器每分钟把当前时段目标温度下发到柜机。
 */
@Service
public class DeviceTempPlanService {

    private static final Logger log = LoggerFactory.getLogger(DeviceTempPlanService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MIN_TEMP = -30;
    private static final int MAX_TEMP = 30;

    private final PermissionService permissionService;
    private final DeviceTempPlanMapper planRepository;
    private final DeviceTempPlanEntryMapper entryRepository;
    private final DeviceInfoMapper deviceRepository;
    private final DeviceServiceClient deviceClient;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
    /** 经 Spring 代理调用本类 @Transactional 方法，避免自调用失效。 */
    private final DeviceTempPlanService self;
    private final ScheduledTaskService taskService;

    public DeviceTempPlanService(PermissionService permissionService,
                                 DeviceTempPlanMapper planRepository,
                                 DeviceTempPlanEntryMapper entryRepository,
                                 DeviceInfoMapper deviceRepository,
                                 DeviceServiceClient deviceClient,
                                 AdminAuditService auditService,
                                 DistributedLockService distributedLockService,
                                 @Lazy DeviceTempPlanService self,
                                 ScheduledTaskService taskService) {
        this.permissionService = permissionService;
        this.planRepository = planRepository;
        this.entryRepository = entryRepository;
        this.deviceRepository = deviceRepository;
        this.deviceClient = deviceClient;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.self = self;
        this.taskService = taskService;
    }

    @Transactional(readOnly = true)
    public DeviceTempPlanDto get(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:device:list");
        requireDevice(deviceId);
        DeviceTempPlan plan = planRepository.findByDeviceId(deviceId).orElse(null);
        return plan == null ? new DeviceTempPlanDto(deviceId, false, List.of()) : toDto(plan);
    }

    @Transactional
    public DeviceTempPlanDto upsert(Long operatorId, String deviceId,
                                    boolean enabled, List<DeviceTempPlanEntryDto> entries) {
        return runWithTempPlanLock(deviceId, () -> doUpsert(operatorId, deviceId, enabled, entries));
    }

    private DeviceTempPlanDto doUpsert(Long operatorId, String deviceId,
                                       boolean enabled, List<DeviceTempPlanEntryDto> entries) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        requireDevice(deviceId);
        if (entries == null || entries.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "温控计划至少需要一个时间点");
        }
        Set<Integer> minutes = new HashSet<>();
        for (DeviceTempPlanEntryDto e : entries) {
            if (e.startMinute() < 0 || e.startMinute() > 1439) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间点须在 00:00-23:59 之间");
            }
            if (e.targetTempC() < MIN_TEMP || e.targetTempC() > MAX_TEMP) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标温度须在 -30°C ~ 30°C 之间");
            }
            if (!minutes.add(e.startMinute())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "时间点不能重复");
            }
        }
        DeviceTempPlan plan = planRepository.findByDeviceId(deviceId).orElseGet(() -> {
            DeviceTempPlan p = new DeviceTempPlan();
            p.setDeviceId(deviceId);
            p.setEnabled(true);
            p.setCreatedAt(Instant.now());
            p.setUpdatedAt(Instant.now());
            planRepository.insert(p);
            return p;
        });
        plan.setEnabled(enabled);
        plan.setUpdatedAt(Instant.now());
        planRepository.updateById(plan);

        entryRepository.deleteByPlanId(plan.getPlanId());
        for (DeviceTempPlanEntryDto e : entries.stream()
                .sorted(Comparator.comparingInt(DeviceTempPlanEntryDto::startMinute)).toList()) {
            DeviceTempPlanEntry row = new DeviceTempPlanEntry();
            row.setPlanId(plan.getPlanId());
            row.setStartMinute(e.startMinute());
            row.setTargetTempC(e.targetTempC());
            entryRepository.insert(row);
        }
        auditService.appendLog(operatorId, "DEVICE_TEMP_PLAN", "DEVICE", deviceId,
                "温控计划更新，条目数=" + entries.size() + "，启用=" + enabled);
        if (enabled) {
            doApplyNow(deviceId);
        }
        return toDto(plan);
    }

    @Transactional
    public DeviceTempPlanDto applyNow(Long operatorId, String deviceId) {
        permissionService.requirePermission(operatorId, "ops:device:edit");
        requireDevice(deviceId);
        return runWithTempPlanLock(deviceId, () -> {
            doApplyNow(deviceId);
            DeviceTempPlan plan = planRepository.findByDeviceId(deviceId).orElse(null);
            return plan == null ? new DeviceTempPlanDto(deviceId, false, List.of()) : toDto(plan);
        });
    }

    /** 当前分钟应执行的目标温度：取最后一个 startMinute <= now 的条目，未到则取当日最后一条（跨日回绕）。 */
    static int targetForMinute(List<DeviceTempPlanEntryDto> entries, int minuteOfDay) {
        DeviceTempPlanEntryDto best = entries.get(entries.size() - 1);
        for (DeviceTempPlanEntryDto e : entries) {
            if (e.startMinute() <= minuteOfDay) {
                best = e;
            }
        }
        return best.targetTempC();
    }

    @Transactional
    void applyNow(String deviceId) {
        runWithTempPlanLock(deviceId, () -> {
            doApplyNow(deviceId);
            return null;
        });
    }

    private void doApplyNow(String deviceId) {
        DeviceTempPlan plan = planRepository.findByDeviceId(deviceId).orElse(null);
        if (plan == null || !plan.isEnabled()) {
            return;
        }
        List<DeviceTempPlanEntryDto> entries = entryRepository.findByPlanId(plan.getPlanId()).stream()
                .map(r -> new DeviceTempPlanEntryDto(r.getEntryId(), r.getStartMinute(), r.getTargetTempC()))
                .sorted(Comparator.comparingInt(DeviceTempPlanEntryDto::startMinute))
                .toList();
        if (entries.isEmpty()) {
            return;
        }
        LocalTime now = LocalTime.now(ZONE);
        int target = targetForMinute(entries, now.getHour() * 60 + now.getMinute());
        DeviceInfo device = deviceRepository.findByIdForUpdate(deviceId).orElse(null);
        if (device == null) {
            return;
        }
        if (device.getTargetTempC() != null && device.getTargetTempC() == target) {
            return;
        }
        device.setTargetTempC(target);
        deviceRepository.save(device);
        if ("ONLINE".equalsIgnoreCase(device.getOnlineStatus())) {
            try {
                deviceClient.requestSetTargetTemp(deviceId, target);
                log.info("temp plan applied device={} target={}", deviceId, target);
            } catch (Exception e) {
                log.warn("temp plan apply command failed device={}: {}", deviceId, e.getMessage());
            }
        }
    }

    /** 每分钟扫描启用的温控计划，按当前时段下发目标温度。 */
    @Scheduled(fixedRate = 60_000)
    public void scheduledApply() {
        if (!taskService.tryBegin("temp-plan", 600)) {
            return;
        }
        for (DeviceTempPlan plan : planRepository.findAllEnabled()) {
            try {
                self.applyNow(plan.getDeviceId());
            } catch (Exception e) {
                log.warn("temp plan scheduled apply failed device={}: {}", plan.getDeviceId(), e.getMessage());
            }
        }
    }

    private DeviceTempPlanDto toDto(DeviceTempPlan plan) {
        List<DeviceTempPlanEntryDto> entries = entryRepository.findByPlanId(plan.getPlanId()).stream()
                .map(r -> new DeviceTempPlanEntryDto(r.getEntryId(), r.getStartMinute(), r.getTargetTempC()))
                .sorted(Comparator.comparingInt(DeviceTempPlanEntryDto::startMinute))
                .toList();
        return new DeviceTempPlanDto(plan.getDeviceId(), plan.isEnabled(), entries);
    }

    private DeviceInfo requireDevice(String deviceId) {
        return deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在: " + deviceId));
    }

    static String tempPlanLockKey(String deviceId) {
        return "device:temp-plan:" + deviceId;
    }

    private <T> T runWithTempPlanLock(String deviceId, java.util.function.Supplier<T> action) {
        if (!distributedLockService.tryLock(tempPlanLockKey(deviceId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "温控计划处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(tempPlanLockKey(deviceId));
        }
    }
}
