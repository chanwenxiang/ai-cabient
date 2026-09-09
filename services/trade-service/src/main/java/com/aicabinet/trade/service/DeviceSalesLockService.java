package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * 营业锁机唯一入口：DB 状态与边端 MQTT 锁机保持一致。
 * 运维指令「锁机停售」与柜机策略「营业锁机/禁售」均经此服务。
 * <p>本地库写入与边端下发分离：先短事务落库，提交后再 MQTT，避免远程成功而事务回滚。
 */
@Service
public class DeviceSalesLockService {

    private static final Logger log = LoggerFactory.getLogger(DeviceSalesLockService.class);

    private final DeviceInfoMapper deviceRepository;
    private final DeviceServiceClient deviceClient;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;
    private final MerchantDeviceIncidentNotifyService incidentNotifyService;
    private final OpsExceptionService opsExceptionService;
    private final DeviceSalesLockService self;

    public DeviceSalesLockService(DeviceInfoMapper deviceRepository,
                                  DeviceServiceClient deviceClient,
                                  AdminAuditService auditService,
                                  DistributedLockService distributedLockService,
                                  @Lazy MerchantDeviceIncidentNotifyService incidentNotifyService,
                                  @Lazy OpsExceptionService opsExceptionService,
                                  @Lazy DeviceSalesLockService self) {
        this.deviceRepository = deviceRepository;
        this.deviceClient = deviceClient;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
        this.incidentNotifyService = incidentNotifyService;
        this.opsExceptionService = opsExceptionService;
        this.self = self;
    }

    /**
     * @param notifyEdge 是否下发 MQTT LOCK/UNLOCK（策略开关与运维按钮均应 true）
     * @return commandId（边端下发或 LOCAL- 兜底）
     */
    public String applySalesLock(Long operatorId, DeviceInfo device, boolean locked,
                                 String reason, boolean notifyEdge) {
        if (device == null || device.getDeviceId() == null || device.getDeviceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备不存在");
        }
        String commandId = runWithDeviceSalesLock(device.getDeviceId(),
                () -> doApplySalesLock(operatorId, device.getDeviceId(), locked, reason, notifyEdge));
        // 与库态对齐，避免调用方仍读到旧的 salesLocked
        device.setSalesLocked(locked);
        if (locked) {
            device.setSalesUnlockedAt(null);
            device.setSalesLockReason(
                    reason == null || reason.isBlank() ? "营业锁机" : reason.trim());
        } else {
            device.setSaleForbidden(false);
            device.setSalesLockReason(null);
            device.setSalesUnlockedAt(java.time.Instant.now());
        }
        return commandId;
    }

    static String deviceSalesLockKey(String deviceId) {
        return "device:sales-lock:" + deviceId;
    }

    private String doApplySalesLock(Long operatorId, String deviceId, boolean locked,
                                    String reason, boolean notifyEdge) {
        PersistResult persisted = self.persistSalesLockState(operatorId, deviceId, locked, reason, notifyEdge);
        String commandId = persisted.localCommandId();
        if (notifyEdge) {
            String mqttCmd = locked ? CabinetConstants.MQTT_CMD_LOCK : CabinetConstants.MQTT_CMD_UNLOCK;
            try {
                commandId = deviceClient.requestOpsCommand(deviceId, mqttCmd);
            } catch (Exception e) {
                log.warn("sales lock mqtt failed device={} locked={} fallback LOCAL: {}",
                        deviceId, locked, e.toString());
            }
        }
        if (locked && !persisted.wasLocked() && incidentNotifyService != null) {
            try {
                incidentNotifyService.notifySalesLocked(deviceId, persisted.reasonText());
            } catch (Exception e) {
                log.warn("sales lock incident notify failed device={}", deviceId, e);
            }
        }
        if (!locked && persisted.wasLocked() && opsExceptionService != null) {
            opsExceptionService.resolveOfflineAutoLockFault(deviceId,
                    "营业已解锁，关闭离线超时停售待办");
        }
        return commandId;
    }

    /**
     * 仅本地写：设备锁态 + 审计。MQTT / 通知在事务提交后执行。
     */
    @Transactional
    public PersistResult persistSalesLockState(Long operatorId, String deviceId, boolean locked,
                                               String reason, boolean notifyEdge) {
        DeviceInfo device = deviceRepository.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
        boolean wasLocked = device.salesLockedEnabled();
        String localCommandId = "LOCAL-" + UUID.randomUUID().toString().substring(0, 8);
        device.setSalesLocked(locked);
        if (locked) {
            device.setSalesUnlockedAt(null);
            device.setSalesLockReason(
                    reason == null || reason.isBlank() ? "营业锁机" : reason.trim());
            deviceRepository.clearSalesUnlockedAt(device.getDeviceId());
        } else {
            device.setSaleForbidden(false);
            device.setSalesLockReason(null);
            device.setSalesUnlockedAt(java.time.Instant.now());
        }
        deviceRepository.save(device);
        String action = locked ? "DEVICE_LOCK" : "DEVICE_UNLOCK";
        String reasonText = reason == null || reason.isBlank() ? "营业锁" : reason.trim();
        auditService.appendLog(operatorId, action, "DEVICE", device.getDeviceId(),
                reasonText
                        + "；指令编号=" + localCommandId
                        + "；是否下发柜机=" + (notifyEdge ? "是" : "否"));
        return new PersistResult(wasLocked, localCommandId, reasonText);
    }

    public record PersistResult(boolean wasLocked, String localCommandId, String reasonText) {}

    private String runWithDeviceSalesLock(String deviceId, java.util.function.Supplier<String> action) {
        if (!distributedLockService.tryLock(deviceSalesLockKey(deviceId), 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备锁机处理中，请稍后重试");
        }
        try {
            return action.get();
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), e);
        } finally {
            distributedLockService.unlock(deviceSalesLockKey(deviceId));
        }
    }
}
