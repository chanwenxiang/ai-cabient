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
    /** SKU 未命中时的扣款阈值兜底（与商品主数据默认一致）。 */
    private static final float DEFAULT_SKU_MIN_CHARGE = 0.92f;
    private static final double DEFAULT_OVERALL_MIN = 0.72d;

    private final SkuCatalogMapper skuCatalogRepository;
    private final SystemConfigService systemConfigService;

    public SettlementConfidenceService(SkuCatalogMapper skuCatalogRepository,
                                       SystemConfigService systemConfigService) {
        this.skuCatalogRepository = skuCatalogRepository;
        this.systemConfigService = systemConfigService;
    }

    /**
     * 二次校验：整体置信度低于全局门槛、单品置信度不足，或存在中等置信度带，
     * 防「识别错但高置信度误扣」。
     * <p>
     * 全局门槛读 {@link SystemConfigService#SETTLEMENT_MIN_CONFIDENCE}；
     * 单品门槛用 SKU {@code minChargeConfidence}。
     */
    public String reviewReasonIfNeeded(VisionServiceClient.RecognitionResult recognition) {
        if (recognition.items().isEmpty()) {
            return null;
        }
        float overallMin = (float) systemConfigService.getDouble(
                SystemConfigService.SETTLEMENT_MIN_CONFIDENCE, DEFAULT_OVERALL_MIN);
        if (recognition.overallConfidence() < overallMin) {
            return String.format("整体识别置信度 %.0f%% 低于自动结算门槛 %.0f%%，需人工审核",
                    recognition.overallConfidence() * 100, overallMin * 100);
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
            float minConf = sku != null ? sku.getMinChargeConfidence() : DEFAULT_SKU_MIN_CHARGE;
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
