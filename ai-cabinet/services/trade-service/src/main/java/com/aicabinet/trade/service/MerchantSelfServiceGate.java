package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.repository.DeviceInfoRepository;
import com.aicabinet.trade.repository.MerchantRepository;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/** 平台开关：商户自助改价 / 改货道 */
@Service
public class MerchantSelfServiceGate {

    private final MerchantRepository merchantRepository;
    private final DeviceInfoRepository deviceRepository;
    private final MerchantScopeService merchantScopeService;

    public MerchantSelfServiceGate(MerchantRepository merchantRepository,
                                   DeviceInfoRepository deviceRepository,
                                   MerchantScopeService merchantScopeService) {
        this.merchantRepository = merchantRepository;
        this.deviceRepository = deviceRepository;
        this.merchantScopeService = merchantScopeService;
    }

    @Transactional(readOnly = true)
    public void requirePricingEdit(Long userId) {
        Set<String> merchantIds = merchantScopeService.allowedMerchantIds(userId);
        if (merchantIds == null || merchantIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED);
        }
        for (String merchantId : merchantIds) {
            Merchant merchant = merchantRepository.findById(merchantId).orElse(null);
            if (merchant != null && merchant.isAllowMerchantPricingEdit()) {
                return;
            }
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "平台未开启商户自助改价");
    }

    @Transactional(readOnly = true)
    public void requirePlanogramEdit(Long userId, String deviceId) {
        merchantScopeService.requireDeviceAccess(userId, deviceId);
        DeviceInfo device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.DEVICE_NOT_FOUND));
        if (device.getMerchantId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "设备未绑定商户");
        }
        Merchant merchant = merchantRepository.findById(device.getMerchantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "商户不存在"));
        if (!merchant.isAllowMerchantPlanogramEdit()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "平台未开启商户自助改货道");
        }
    }

    @Transactional(readOnly = true)
    public boolean canEditPlanogram(Long userId, String deviceId) {
        try {
            requirePlanogramEdit(userId, deviceId);
            return true;
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode() == HttpStatus.FORBIDDEN) {
                return false;
            }
            throw ex;
        }
    }
}
