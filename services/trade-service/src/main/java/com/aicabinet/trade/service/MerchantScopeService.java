package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.domain.OpsUserDeviceScope;
import com.aicabinet.trade.domain.OpsUserDeviceScopePref;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.mapper.OpsRoleMapper;
import com.aicabinet.trade.mapper.OpsUserDeviceScopeMapper;
import com.aicabinet.trade.mapper.OpsUserDeviceScopePrefMapper;
import com.aicabinet.trade.mapper.OpsUserMerchantMapper;
import com.aicabinet.trade.mapper.OpsUserRoleMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运营账号商户数据范围：未绑定商户 = 全局；绑定后仅可见对应商户及其下级组织的设备/订单。
 * admin 角色始终全局。
 */
@Service
public class MerchantScopeService {

    private final OpsUserMerchantMapper userMerchantRepository;
    private final OpsUserRoleMapper userRoleRepository;
    private final OpsRoleMapper roleRepository;
    private final DeviceInfoMapper deviceRepository;
    private final MerchantMapper merchantRepository;
    private final OpsUserDeviceScopeMapper deviceScopeMapper;
    private final OpsUserDeviceScopePrefMapper deviceScopePrefMapper;
    private final CabinetMetrics cabinetMetrics;

    public MerchantScopeService(OpsUserMerchantMapper userMerchantRepository,
                                OpsUserRoleMapper userRoleRepository,
                                OpsRoleMapper roleRepository,
                                DeviceInfoMapper deviceRepository,
                                MerchantMapper merchantRepository,
                                OpsUserDeviceScopeMapper deviceScopeMapper,
                                OpsUserDeviceScopePrefMapper deviceScopePrefMapper,
                                CabinetMetrics cabinetMetrics) {
        this.userMerchantRepository = userMerchantRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.deviceRepository = deviceRepository;
        this.merchantRepository = merchantRepository;
        this.deviceScopeMapper = deviceScopeMapper;
        this.deviceScopePrefMapper = deviceScopePrefMapper;
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
        Set<String> roots = userMerchantRepository.findByIdUserId(operatorId).stream()
                .map(m -> m.getId().getMerchantId())
                .collect(Collectors.toCollection(HashSet::new));
        return expandWithDescendants(roots);
    }

    /** 绑定商户 + 全部下级（parent_merchant_id 树） */
    @Transactional(readOnly = true)
    public Set<String> expandWithDescendants(Set<String> roots) {
        if (roots == null || roots.isEmpty()) {
            return Set.of();
        }
        Map<String, List<String>> children = new HashMap<>();
        for (Merchant m : merchantRepository.findAll()) {
            String parent = m.getParentMerchantId();
            if (parent == null || parent.isBlank()) {
                continue;
            }
            children.computeIfAbsent(parent.trim(), k -> new java.util.ArrayList<>()).add(m.getMerchantId());
        }
        Set<String> out = new HashSet<>(roots);
        Queue<String> q = new ArrayDeque<>(roots);
        while (!q.isEmpty()) {
            String id = q.poll();
            for (String child : children.getOrDefault(id, List.of())) {
                if (out.add(child)) {
                    q.add(child);
                }
            }
        }
        return out;
    }

    /** null = 全部设备；空集 = 无权限；非空 = 限定设备（商户范围 ∩ 货柜范围） */
    @Transactional(readOnly = true)
    public Set<String> allowedDeviceIds(Long operatorId) {
        Set<String> merchantIds = allowedMerchantIds(operatorId);
        Set<String> byMerchant;
        if (merchantIds == null) {
            byMerchant = null;
        } else if (merchantIds.isEmpty()) {
            return Set.of();
        } else {
            byMerchant = deviceRepository.findByMerchantIdIn(merchantIds).stream()
                    .map(DeviceInfo::getDeviceId)
                    .collect(Collectors.toSet());
        }
        return intersectDeviceCabinetScope(operatorId, byMerchant);
    }

    /**
     * 货柜级数据范围：pref.scope_mode=PARTIAL 时与勾选柜求交；ALL / 无 pref 不额外限制。
     * admin 全局账号不受货柜范围限制。
     */
    @Transactional(readOnly = true)
    public Set<String> intersectDeviceCabinetScope(Long operatorId, Set<String> merchantScopedDevices) {
        if (hasAdminRole(operatorId)) {
            return merchantScopedDevices;
        }
        String mode = deviceScopePrefMapper.findById(operatorId)
                .map(OpsUserDeviceScopePref::getScopeMode)
                .orElse("ALL");
        if (!"PARTIAL".equalsIgnoreCase(mode)) {
            return merchantScopedDevices;
        }
        Set<String> picked = deviceScopeMapper.findByUserId(operatorId).stream()
                .map(OpsUserDeviceScope::getDeviceId)
                .collect(Collectors.toCollection(HashSet::new));
        if (merchantScopedDevices == null) {
            return picked;
        }
        picked.retainAll(merchantScopedDevices);
        return picked;
    }

    @Transactional(readOnly = true)
    public List<DeviceInfo> allowedDevices(Long operatorId) {
        Set<String> deviceIds = allowedDeviceIds(operatorId);
        if (deviceIds == null) {
            return deviceRepository.findAll();
        }
        if (deviceIds.isEmpty()) {
            return List.of();
        }
        return deviceRepository.findByDeviceIdIn(deviceIds);
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
