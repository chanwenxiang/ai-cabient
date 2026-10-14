package com.aicabinet.trade.support;

import com.aicabinet.trade.domain.SkuCatalog;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * SKU 价格限价规则（V350，CB-037）——商户端覆盖价与 admin 设备级定价/全局基准价
 * **共用同一套规则**，防止两处各自漂移（历史上商户端有校验、admin 无校验的落差即由此生）。
 *
 * <p>限价语义（2026-10-14 用户拍板）：
 * <ul>
 *   <li>最低价：SKU 显式 {@code min_price_cents}；<b>null = 不限</b>（仅要求 &gt;0）。
 *       用户明确否决「不低于进货成本」硬线——时段/清仓折扣本来就能打穿成本，
 *       成本线只挡手设价不自洽；清仓/引流打折必须放行，低于成本由前端提醒不阻断。</li>
 *   <li>最高价：SKU 显式 {@code max_price_cents}；null = 默认基准价 2 倍
 *       （{@value #DEFAULT_MAX_MULTIPLIER} 倍，沿用商户端 M2 以来口径）。</li>
 * </ul>
 */
public final class SkuPriceBounds {

    /** 最高价缺省倍数：商户端 M2 以来的既定口径，勿单方面改动（CB-037）。 */
    public static final int DEFAULT_MAX_MULTIPLIER = 2;

    private SkuPriceBounds() {}

    /** 最低可设价（分）：显式 minPriceCents 优先；未设置 = 1 分（仅要求正数，不做成本硬线）。 */
    public static int minAllowedPrice(SkuCatalog sku) {
        if (sku.getMinPriceCents() != null && sku.getMinPriceCents() > 0) {
            return sku.getMinPriceCents();
        }
        return 1;
    }

    /** 最高可设价（分）：显式 maxPriceCents 优先；未设置 = max(基准价, 基准价×2)。 */
    public static int maxAllowedPrice(SkuCatalog sku) {
        if (sku.getMaxPriceCents() != null && sku.getMaxPriceCents() > 0) {
            return sku.getMaxPriceCents();
        }
        return Math.max(sku.getPriceCents() * DEFAULT_MAX_MULTIPLIER, sku.getPriceCents());
    }

    /** 校验一个「可设价格」（覆盖价/新基准价）落在 [min, max] 且 &gt;0，越界 400。 */
    public static void validatePrice(SkuCatalog sku, int priceCents) {
        if (priceCents <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "价格必须大于 0");
        }
        int min = minAllowedPrice(sku);
        int max = maxAllowedPrice(sku);
        if (priceCents < min) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "价格不能低于最低限价 ¥" + (min / 100.0));
        }
        if (priceCents > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "价格不能高于最高限价 ¥" + (max / 100.0));
        }
    }

    /** 校验限价设置自身自洽：min ≤ max（都设置时），否则 400。 */
    public static void validateBounds(Integer minPriceCents, Integer maxPriceCents) {
        if (minPriceCents != null && maxPriceCents != null
                && minPriceCents > 0 && maxPriceCents > 0 && minPriceCents > maxPriceCents) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最低限价不能高于最高限价");
        }
    }
}
