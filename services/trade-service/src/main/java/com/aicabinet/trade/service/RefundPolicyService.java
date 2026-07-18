package com.aicabinet.trade.service;

import com.aicabinet.common.enums.RefundPolicy;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefundPolicyService {

    public static final String DEFAULT_POLICY_KEY = SystemConfigService.REFUND_DEFAULT_POLICY;

    private final DeviceInfoMapper deviceRepository;
    private final SystemConfigService systemConfigService;

    public RefundPolicyService(DeviceInfoMapper deviceRepository,
                               SystemConfigService systemConfigService) {
        this.deviceRepository = deviceRepository;
        this.systemConfigService = systemConfigService;
    }

    @Transactional(readOnly = true)
    public RefundPolicy globalDefault() {
        return RefundPolicy.fromOrDefault(
                systemConfigService.getValue(DEFAULT_POLICY_KEY, RefundPolicy.AUTO_REFUND.name()),
                RefundPolicy.AUTO_REFUND);
    }

    /**
     * 解析柜机生效策略：设备覆盖优先，否则全局默认。
     */
    @Transactional(readOnly = true)
    public RefundPolicy resolveForDevice(String deviceId) {
        if (deviceId != null && !deviceId.isBlank()) {
            DeviceInfo device = deviceRepository.findById(deviceId.trim()).orElse(null);
            if (device != null) {
                RefundPolicy override = RefundPolicy.from(device.getRefundPolicy());
                if (override != null) {
                    return override;
                }
            }
        }
        return globalDefault();
    }

    @Transactional(readOnly = true)
    public boolean allowsAutoRefund(String deviceId) {
        return resolveForDevice(deviceId) == RefundPolicy.AUTO_REFUND;
    }

    /** 设备上存储值：空表示继承全局。 */
    public static String normalizeStored(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toUpperCase();
        if ("INHERIT".equals(v) || "DEFAULT".equals(v) || "NULL".equals(v) || "-".equals(v)) {
            return null;
        }
        RefundPolicy p = RefundPolicy.from(v);
        return p != null ? p.name() : null;
    }
}
