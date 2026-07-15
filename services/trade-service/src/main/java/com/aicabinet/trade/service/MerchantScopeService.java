package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.OpsRoleMapper;
import com.aicabinet.trade.mapper.OpsUserMerchantMapper;
import com.aicabinet.trade.mapper.OpsUserRoleMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运营账号商户数据范围：未绑定商户 = 全局；绑定后仅可见对应商户的设备/订单。
 * admin 角色始终全局。
 */
@Service
public class MerchantScopeService {

    private final OpsUserMerchantMapper userMerchantRepository;
    private final OpsUserRoleMapper userRoleRepository;
    private final OpsRoleMapper roleRepository;
    private final DeviceInfoMapper deviceRepository;
    private final CabinetMetrics cabinetMetrics;

    public MerchantScopeService(OpsUserMerchantMapper userMerchantRepository,
                                OpsUserRoleMapper userRoleRepository,
                                OpsRoleMapper roleRepository,
                                DeviceInfoMapper deviceRepository,
                                CabinetMetrics cabinetMetrics) {
        this.userMerchantRepository = userMerchantRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.deviceRepository = deviceRepository;
        this.cabinetMetrics = cabinetMetrics;
    }

    @Transactional(readOnly = true)
    public boolean isGlobalScope(Long operatorId) {
        if (hasAdminRole(operatorId)) {
            return true;
        }
        return !userMerchantRepository.existsByIdUserId(operatorId);
    }

    @Transactional(readOnly = true)
    public Set<String> allowedMerchantIds(Long operatorId) {
        if (isGlobalScope(operatorId)) {
            return null;
        }
        return userMerchantRepository.findByIdUserId(operatorId).stream()
                .map(m -> m.getId().getMerchantId())
                .collect(Collectors.toSet());
    }

    /** null = 全部设备；空集 = 无权限；非空 = 限定设备 */
    @Transactional(readOnly = true)
    public Set<String> allowedDeviceIds(Long operatorId) {
        Set<String> merchantIds = allowedMerchantIds(operatorId);
        if (merchantIds == null) {
            return null;
        }
        if (merchantIds.isEmpty()) {
            return Set.of();
        }
        return deviceRepository.findByMerchantIdIn(merchantIds).stream()
                .map(DeviceInfo::getDeviceId)
                .collect(Collectors.toSet());
    }

    @Transactional(readOnly = true)
    public List<DeviceInfo> allowedDevices(Long operatorId) {
        Set<String> merchantIds = allowedMerchantIds(operatorId);
        if (merchantIds == null) {
            return deviceRepository.findAll();
        }
        if (merchantIds.isEmpty()) {
            return List.of();
        }
        return deviceRepository.findByMerchantIdIn(merchantIds);
    }

    public void requireMerchantAccess(Long operatorId, String merchantId) {
        Set<String> allowed = allowedMerchantIds(operatorId);
        if (allowed == null) {
            return;
        }
        if (merchantId == null || !allowed.contains(merchantId)) {
            cabinetMetrics.recordMerchantScopeDenied("merchant");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED);
        }
    }

    public void requireDeviceAccess(Long operatorId, String deviceId) {
        Set<String> allowedDevices = allowedDeviceIds(operatorId);
        if (allowedDevices == null) {
            return;
        }
        if (deviceId == null || !allowedDevices.contains(deviceId)) {
            cabinetMetrics.recordMerchantScopeDenied("device");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED);
        }
    }

    public void requireDeviceFilter(Long operatorId, String deviceId) {
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        requireDeviceAccess(operatorId, deviceId.trim());
    }

    public Collection<String> intersectDeviceFilter(Long operatorId, String requestedDeviceId) {
        Set<String> allowed = allowedDeviceIds(operatorId);
        if (requestedDeviceId != null && !requestedDeviceId.isBlank()) {
            String dev = requestedDeviceId.trim();
            requireDeviceAccess(operatorId, dev);
            return List.of(dev);
        }
        if (allowed == null) {
            return null;
        }
        return allowed.isEmpty() ? Collections.emptyList() : allowed;
    }

    private boolean hasAdminRole(Long operatorId) {
        return userRoleRepository.findByIdUserId(operatorId).stream()
                .anyMatch(ur -> roleRepository.findById(ur.getId().getRoleId())
                        .map(r -> "admin".equals(r.getRoleKey()))
                        .orElse(false));
    }
}
