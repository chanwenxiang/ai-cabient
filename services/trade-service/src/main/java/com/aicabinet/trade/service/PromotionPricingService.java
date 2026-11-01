package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 活动定价（v1，CB-082；用户 2026-11-01 拍板）：
 * <ul>
 *   <li>只支持三种 SKU 级类型：{@code SKU_REDUCE}（立减）/ {@code SKU_DISCOUNT}（折扣）/ {@code SKU_FIXED_PRICE}（一口价）；</li>
 *   <li>活动价**优于**当前单价才生效（一口价 ≥ 原价不生效，对齐参照系统的提示语）；</li>
 *   <li>不允许 0 元：最低 1 分；</li>
 *   <li>命中活动价的订单**禁用券**（调用方据 {@code Applied} 非空跳过券选择）；</li>
 *   <li>核销限制：{@code user_limit}（每人订单数；0/负数视为不限）+ rule_config.totalOrderLimit（活动总单数，可空）。</li>
 * </ul>
 */
@Service
public class PromotionPricingService {

    private static final Logger log = LoggerFactory.getLogger(PromotionPricingService.class);
    private static final int MIN_PRICE_CENTS = 1;

    public record Applied(long activityId, int unitPriceCents, int discountPerUnitCents) {}

    private final PromotionActivityMapper activityRepository;
    private final CabinetOrderLineMapper orderLineRepository;
    private final DeviceInfoMapper deviceRepository;
    private final ObjectMapper objectMapper;

    public PromotionPricingService(PromotionActivityMapper activityRepository,
                                   CabinetOrderLineMapper orderLineRepository,
                                   DeviceInfoMapper deviceRepository,
                                   ObjectMapper objectMapper) {
        this.activityRepository = activityRepository;
        this.orderLineRepository = orderLineRepository;
        this.deviceRepository = deviceRepository;
        this.objectMapper = objectMapper;
    }

    /** 命中则返回**最低**的活动单价；未命中返回 empty。 */
    public Optional<Applied> resolveUnitPrice(Long userId, String deviceId, String skuId, int baseUnitPriceCents) {
        if (deviceId == null || skuId == null || baseUnitPriceCents <= MIN_PRICE_CENTS) {
            return Optional.empty();
        }
        String merchantId = deviceRepository.findById(deviceId)
                .map(DeviceInfo::getMerchantId).orElse(null);
        Applied best = null;
        for (PromotionActivity a : activityRepository.findPricedCandidates(deviceId, merchantId, skuId)) {
            Integer price = priceOf(a, baseUnitPriceCents);
            if (price == null || price >= baseUnitPriceCents || price < MIN_PRICE_CENTS) {
                continue;
            }
            if (!withinUsageLimits(a, userId)) {
                continue;
            }
            int discount = baseUnitPriceCents - price;
            if (best == null || discount > best.discountPerUnitCents()) {
                best = new Applied(a.getActivityId(), price, discount);
            }
        }
        return Optional.ofNullable(best);
    }

    private Integer priceOf(PromotionActivity a, int base) {
        String type = a.getActivityType() == null ? "" : a.getActivityType().toUpperCase();
        return switch (type) {
            case "SKU_REDUCE" -> a.getReduceCents() == null ? null
                    : Math.max(MIN_PRICE_CENTS, base - a.getReduceCents());
            case "SKU_DISCOUNT" -> a.getDiscountBps() == null ? null
                    : Math.max(MIN_PRICE_CENTS, (int) ((long) base * (10_000 - a.getDiscountBps()) / 10_000));
            case "SKU_FIXED_PRICE" -> a.getFixedPriceCents() == null ? null : a.getFixedPriceCents();
            default -> null;
        };
    }

    /** user_limit>0 ⇒ 每人最多 N 单；totalOrderLimit>0 ⇒ 活动总共最多 N 单。 */
    private boolean withinUsageLimits(PromotionActivity a, Long userId) {
        if (a.getUserLimit() > 0 && userId != null
                && orderLineRepository.countOrdersForActivity(a.getActivityId(), userId) >= a.getUserLimit()) {
            return false;
        }
        long totalLimit = readTotalOrderLimit(a);
        if (totalLimit > 0
                && orderLineRepository.countOrdersForActivity(a.getActivityId(), null) >= totalLimit) {
            return false;
        }
        return true;
    }

    private long readTotalOrderLimit(PromotionActivity a) {
        String json = a.getRuleConfig();
        if (json == null || json.isBlank()) {
            return 0L;
        }
        try {
            JsonNode node = objectMapper.readTree(json).get("totalOrderLimit");
            if (node != null && node.isNumber()) {
                return Math.max(0L, node.asLong());
            }
        } catch (Exception e) {
            log.warn("promotion rule_config parse failed activityId={} err={}", a.getActivityId(), e.getMessage());
        }
        return 0L;
    }
}
