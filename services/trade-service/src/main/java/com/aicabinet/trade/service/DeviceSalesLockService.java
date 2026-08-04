package com.aicabinet.trade.service;

import com.aicabinet.common.constants.CabinetConstants;
import com.aicabinet.trade.client.DeviceServiceClient;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 营业锁机唯一入口：DB 状态与边端 MQTT 锁机保持一致。
 * 运维指令「锁机停售」与柜机策略「营业锁机/禁售」均经此服务。
 */
@Service
public class DeviceSalesLockService {

    private static final Logger log = LoggerFactory.getLogger(DeviceSalesLockService.class);

    private final DeviceInfoMapper deviceRepository;
    private final DeviceServiceClient deviceClient;
    private final AdminAuditService auditService;

    public DeviceSalesLockService(DeviceInfoMapper deviceRepository,
                                  DeviceServiceClient deviceClient,
                                  AdminAuditService auditService) {
        this.deviceRepository = deviceRepository;
        this.deviceClient = deviceClient;
        this.auditService = auditService;
    }

    /**
     * @param notifyEdge 是否下发 MQTT LOCK/UNLOCK（策略开关与运维按钮均应 true）
     * @return commandId（边端下发或 LOCAL- 兜底）
     */
    @Transactional
    public String applySalesLock(Long operatorId, DeviceInfo device, boolean locked,
                                 String reason, boolean notifyEdge) {
        String commandId = "LOCAL-" + UUID.randomUUID().toString().substring(0, 8);
        if (notifyEdge) {
            String mqttCmd = locked ? CabinetConstants.MQTT_CMD_LOCK : CabinetConstants.MQTT_CMD_UNLOCK;
            try {
                commandId = deviceClient.requestOpsCommand(device.getDeviceId(), mqttCmd);
            } catch (Exception e) {
                log.warn("sales lock mqtt failed device={} locked={} fallback LOCAL: {}",
                        device.getDeviceId(), locked, e.toString());
            }
        }
        device.setSalesLocked(locked);
        if (locked) {
            // 锁机即消费者不可开门；禁售场景也走同一营业锁
        } else {
            // 解锁营业时同步清掉禁售，避免策略里仍显示「禁售但已解锁」的矛盾态
            device.setSaleForbidden(false);
        }
        deviceRepository.save(device);
        String action = locked ? "DEVICE_LOCK" : "DEVICE_UNLOCK";
        auditService.record(operatorId, action, "DEVICE", device.getDeviceId(),
                (reason == null || reason.isBlank() ? "营业锁" : reason.trim())
                        + "；指令编号=" + commandId
                        + "；是否下发柜机=" + (notifyEdge ? "是" : "否"));
        return commandId;
    }
}
