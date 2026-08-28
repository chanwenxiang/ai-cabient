package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.support.ApiMessages;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 平台功能包：RBAC 并集开包 + 数据范围按「商户自身是否开包」裁剪。 */
@Service
public class MerchantFeaturePackService {

    private final MerchantMapper merchantRepository;
    private final DeviceInfoMapper deviceRepository;
    private final MerchantScopeService merchantScopeService;
    private final MerchantFeaturePackService self;

    public MerchantFeaturePackService(MerchantMapper merchantRepository,
                                      DeviceInfoMapper deviceRepository,
                                      MerchantScopeService merchantScopeService,
                                      @Lazy MerchantFeaturePackService self) {
        this.merchantRepository = merchantRepository;
        this.deviceRepository = deviceRepository;
        this.merchantScopeService = merchantScopeService;
        this.self = self;
    }

    @Transactional(readOnly = true)
    public Set<String> enabledPacksForUser(Long userId) {
        Set<String> allowed = merchantScopeService.allowedMerchantIds(userId);
        if (merchantScopeService.isGlobalScope(userId)) {
            return Set.of(MerchantFeaturePacks.FIELD, MerchantFeaturePacks.BIZ, MerchantFeaturePacks.TEAM);
        }
        if (allowed.isEmpty()) {
            return Set.of();
        }
        Set<String> packs = new LinkedHashSet<>();
        for (Merchant m : loadMerchants(allowed)) {
            if (m.isPackFieldEnabled()) {
                packs.add(MerchantFeaturePacks.FIELD);
            }
            if (m.isPackBizEnabled()) {
                packs.add(MerchantFeaturePacks.BIZ);
            }
            if (m.isPackTeamEnabled()) {
                packs.add(MerchantFeaturePacks.TEAM);
            }
        }
        return packs;
    }

    @Transactional(readOnly = true)
    public boolean isPermEnabledForUser(Long userId, String permCode) {
        if (MerchantFeaturePacks.isPackAgnostic(permCode)) {
            return true;
        }
        String pack = MerchantFeaturePacks.packForPerm(permCode);
        if (pack == null) {
            return true;
        }
        return self.enabledPacksForUser(userId).contains(pack);
    }

    @Transactional(readOnly = true)
    public List<String> filterPermissions(Long userId, List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }
        Set<String> packs = self.enabledPacksForUser(userId);
        List<String> out = new ArrayList<>(permissions.size());
        for (String p : permissions) {
            if (MerchantFeaturePacks.isPackAgnostic(p)) {
                out.add(p);
                continue;
            }
            String pack = MerchantFeaturePacks.packForPerm(p);
            if (pack == null || packs.contains(pack)) {
                out.add(p);
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<String> enabledPacksList(Long userId) {
        return self.enabledPacksForUser(userId).stream().sorted().toList();
    }

    /** 数据范围 ∩ 开了指定功能包的商户；全局账号返回库内启用该包的商户集合。 */
    @Transactional(readOnly = true)
    public Set<String> allowedMerchantIdsForPack(Long userId, String pack) {
        Set<String> allowed = merchantScopeService.allowedMerchantIds(userId);
        return self.filterMerchantIdsByPack(allowed, pack);
    }

    @Transactional(readOnly = true)
    public Set<String> filterMerchantIdsByPack(Set<String> merchantIds, String pack) {
        if (merchantIds == null) {
            merchantIds = merchantRepository.findAll().stream()
                    .map(Merchant::getMerchantId)
                    .collect(Collectors.toCollection(HashSet::new));
        }
        if (merchantIds.isEmpty() || pack == null || pack.isBlank()) {
            return Set.copyOf(merchantIds);
        }
        Set<String> out = new HashSet<>();
        for (Merchant m : loadMerchants(merchantIds)) {
            if (merchantEnablesPack(m, pack)) {
                out.add(m.getMerchantId());
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<DeviceInfo> allowedDevicesForPack(Long userId, String pack) {
        Set<String> merchantIds = self.allowedMerchantIdsForPack(userId, pack);
        Set<String> byMerchant;
        if (merchantIds == null) {
            byMerchant = null;
        } else if (merchantIds.isEmpty()) {
            return List.of();
        } else {
            byMerchant = deviceRepository.findByMerchantIdIn(merchantIds).stream()
                    .map(DeviceInfo::getDeviceId)
                    .collect(Collectors.toCollection(HashSet::new));
        }
        Set<String> scoped = merchantScopeService.intersectDeviceCabinetScope(userId, byMerchant);
        if (scoped == null) {
            return deviceRepository.findAll();
        }
        if (scoped.isEmpty()) {
            return List.of();
        }
        return deviceRepository.findByDeviceIdIn(scoped);
    }

    @Transactional(readOnly = true)
    public Set<String> allowedDeviceIdsForPack(Long userId, String pack) {
        if (merchantScopeService.isGlobalScope(userId)) {
            return merchantScopeService.intersectDeviceCabinetScope(userId, null);
        }
        Set<String> merchantIds = self.allowedMerchantIdsForPack(userId, pack);
        if (merchantIds.isEmpty()) {
            return Set.of();
        }
        Set<String> byMerchant = deviceRepository.findByMerchantIdIn(merchantIds).stream()
                .map(DeviceInfo::getDeviceId)
                .collect(Collectors.toCollection(HashSet::new));
        return merchantScopeService.intersectDeviceCabinetScope(userId, byMerchant);
    }

    /** 与 {@link MerchantScopeService#intersectDeviceFilter} 相同语义，但限制在功能包内。 */
    @Transactional(readOnly = true)
    public Collection<String> intersectDeviceFilterForPack(Long userId, String requestedDeviceId, String pack) {
        Set<String> allowed = self.allowedDeviceIdsForPack(userId, pack);
        if (requestedDeviceId != null && !requestedDeviceId.isBlank()) {
            String dev = requestedDeviceId.trim();
            self.requireDevicePack(userId, dev, pack);
            return List.of(dev);
        }
        if (allowed == null) {
            return deviceRepository.findAll().stream()
                    .map(DeviceInfo::getDeviceId)
                    .toList();
        }
        return allowed.isEmpty() ? Collections.emptyList() : allowed;
    }

    @Transactional(readOnly = true)
    public void requireDevicePack(Long userId, String deviceId, String pack) {
        merchantScopeService.requireDeviceAccess(userId, deviceId);
        DeviceInfo device = deviceRepository.findById(deviceId).orElse(null);
        if (device == null || device.getMerchantId() == null || device.getMerchantId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED);
        }
        Set<String> packMerchants = self.allowedMerchantIdsForPack(userId, pack);
        if (packMerchants != null && !packMerchants.contains(device.getMerchantId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该商户未开通对应功能包");
        }
    }

    @Transactional(readOnly = true)
    public void requireMerchantPack(Long userId, String merchantId, String pack) {
        merchantScopeService.requireMerchantAccess(userId, merchantId);
        Set<String> packMerchants = self.allowedMerchantIdsForPack(userId, pack);
        if (packMerchants != null && (merchantId == null || !packMerchants.contains(merchantId))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该商户未开通对应功能包");
        }
    }

    private List<Merchant> loadMerchants(Collection<String> merchantIds) {
        if (merchantIds == null || merchantIds.isEmpty()) {
            return List.of();
        }
        return merchantRepository.selectList(
                Wrappers.<Merchant>lambdaQuery().in(Merchant::getMerchantId, merchantIds));
    }

    private static boolean merchantEnablesPack(Merchant m, String pack) {
        if (MerchantFeaturePacks.FIELD.equals(pack)) {
            return m.isPackFieldEnabled();
        }
        if (MerchantFeaturePacks.BIZ.equals(pack)) {
            return m.isPackBizEnabled();
        }
        if (MerchantFeaturePacks.TEAM.equals(pack)) {
            return m.isPackTeamEnabled();
        }
        return true;
    }
}
