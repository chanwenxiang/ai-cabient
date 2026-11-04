package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Merchant;
import com.aicabinet.trade.mapper.MerchantMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/** 平台开关：商户自助改价（改货道开关随批 3b 货道面退场一并摘除——已无货道写入口可守）。 */
@Service
public class MerchantSelfServiceGate {

    private final MerchantMapper merchantRepository;
    private final MerchantScopeService merchantScopeService;

    public MerchantSelfServiceGate(MerchantMapper merchantRepository,
                                   MerchantScopeService merchantScopeService) {
        this.merchantRepository = merchantRepository;
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
}
