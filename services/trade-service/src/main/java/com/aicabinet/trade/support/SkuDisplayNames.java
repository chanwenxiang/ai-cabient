package com.aicabinet.trade.support;

import com.aicabinet.trade.domain.SkuCatalog;

/**
 * 商品展示名：名称+规格（如东鹏特饮250ml），与前端 formatReplenRequestProduct 口径一致。
 */
public final class SkuDisplayNames {

    private SkuDisplayNames() {}

    public static String of(SkuCatalog catalog) {
        if (catalog == null) {
            return null;
        }
        return of(catalog.getSkuName(), catalog.getSpec(), catalog.getSkuId());
    }

    public static String of(String skuName, String spec, String skuIdFallback) {
        String name = skuName == null ? "" : skuName.trim();
        String trimmedSpec = spec == null ? "" : spec.trim();
        if (!name.isEmpty() && !trimmedSpec.isEmpty() && !name.contains(trimmedSpec)) {
            return name + trimmedSpec;
        }
        if (!name.isEmpty()) {
            return name;
        }
        if (skuIdFallback != null && !skuIdFallback.isBlank()) {
            return skuIdFallback.trim();
        }
        return null;
    }
}
