package com.aicabinet.trade.service;

import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SettlementConfidenceService {

    private static final float MEDIUM_BAND_LOW = 0.80f;

    private final SkuCatalogMapper skuCatalogRepository;

    public SettlementConfidenceService(SkuCatalogMapper skuCatalogRepository) {
        this.skuCatalogRepository = skuCatalogRepository;
    }

    /**
     * 二次校验：vision 整体通过但单品置信度不足，或存在中等置信度带，防「识别错但高置信度误扣」。
     */
    public String reviewReasonIfNeeded(VisionServiceClient.RecognitionResult recognition) {
        if (recognition.items().isEmpty()) {
            return null;
        }
        List<String> skuIds = recognition.items().stream()
                .map(VisionServiceClient.RecognizedItem::skuId)
                .distinct()
                .toList();
        Map<String, SkuCatalog> skus = skuCatalogRepository.findAllById(skuIds).stream()
                .collect(Collectors.toMap(SkuCatalog::getSkuId, s -> s));

        boolean hasMediumBand = false;
        for (VisionServiceClient.RecognizedItem item : recognition.items()) {
            SkuCatalog sku = skus.get(item.skuId());
            float minConf = sku != null ? sku.getMinChargeConfidence() : 0.92f;
            if (item.confidence() < minConf) {
                String name = sku != null ? sku.getSkuName() : item.skuId();
                return String.format("商品「%s」置信度 %.0f%% 低于扣款阈值 %.0f%%，需人工审核",
                        name, item.confidence() * 100, minConf * 100);
            }
            if (item.confidence() >= MEDIUM_BAND_LOW && item.confidence() < minConf + 0.03f) {
                hasMediumBand = true;
            }
        }

        if (hasMediumBand && recognition.overallConfidence() >= 0.90f) {
            return "存在中等置信度商品且整体置信偏高，防误扣需人工审核";
        }
        return null;
    }
}
