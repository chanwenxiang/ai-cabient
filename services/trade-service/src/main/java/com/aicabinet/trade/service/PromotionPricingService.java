package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
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

    /** 结算链兜底用的最低单价（与「不允许 0 元购」同口径）。 */
    public static int minPriceCents() {
        return MIN_PRICE_CENTS;
    }

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
    /**
     * 活动价原语：{@code baseUnitPriceCents} 必须是**目录价**，返回值只代表「活动本身的价格」。
     *
     * <p>会员价与活动价<b>不叠加、取优</b>的门面是 {@link #resolveBest}/{@link #resolveBestForOrder}——
     * 直接把会员价当 base 传进来即等于双重优惠（2026-11-12 拍板口径，勿回潮）。</p>
     */
    public Optional<Applied> resolveUnitPrice(Long userId, String deviceId, String skuId, int baseUnitPriceCents) {
        return resolveUnitPrice(userId, deviceId, resolveMerchantId(deviceId), skuId, baseUnitPriceCents);
    }

    /** 批量展示场景：merchantId 由调用方取一次，避免逐 SKU 重复查设备。 */
    public Optional<Applied> resolveUnitPrice(Long userId, String deviceId, String merchantId,
                                              String skuId, int baseUnitPriceCents) {
        return resolveUnitPrice(userId, deviceId, merchantId, skuId, baseUnitPriceCents, null, false);
    }

    /**
     * 展示 / 结算共用的**会员价与活动价取优**（2026-11-12 拍板：两者<b>不叠加</b>）。
     *
     * <p>口径：活动价一律以**目录价**为基价计算（绝不把会员价再当活动基价，否则等于双重优惠），
     * 再与会员价取更低者。活动价 <b>不低于</b> 会员价 ⇒ 视为未命中：不核销限购、不写活动留痕
     * （{@code activity_id} 为空、{@code activity_discount_cents} 为 0），订单上只体现会员价一档。</p>
     *
     * <p>返回的 {@code discountPerUnitCents} 是「相对本单应付基准（会员价）少收的每件金额」，
     * 即落 {@code cabinet_order_line.activity_discount_cents} 的值 ⇒ 恒等式
     * {@code gross − member − activity − coupon = total} 仍成立（member 留档仍是 目录价−会员价）。</p>
     */
    public Optional<Applied> resolveBest(Long userId, String deviceId, String merchantId,
                                        String skuId, int listUnitPriceCents, int memberUnitPriceCents) {
        return resolveUnitPrice(userId, deviceId, merchantId, skuId, listUnitPriceCents, null, false)
                .filter(a -> a.unitPriceCents() < memberUnitPriceCents)
                .map(a -> new Applied(a.activityId(), a.unitPriceCents(), memberUnitPriceCents - a.unitPriceCents()));
    }

    /** 取优的展示口径（商户由设备反查一次，批量场景请用带 merchantId 的重载）。 */
    public Optional<Applied> resolveBest(Long userId, String deviceId, String skuId,
                                        int listUnitPriceCents, int memberUnitPriceCents) {
        return resolveBest(userId, deviceId, resolveMerchantId(deviceId), skuId,
                listUnitPriceCents, memberUnitPriceCents);
    }

    /** 取优 + 结算语义（先锁候选活动行再判限购，并排除本单自身已核销的行）；口径同 {@link #resolveBest}。 */
    public Optional<Applied> resolveBestForOrder(Long userId, String deviceId, String orderId, String skuId,
                                                int listUnitPriceCents, int memberUnitPriceCents) {
        return resolveUnitPrice(userId, deviceId, resolveMerchantId(deviceId), skuId,
                listUnitPriceCents, orderId, true)
                .filter(a -> a.unitPriceCents() < memberUnitPriceCents)
                .map(a -> new Applied(a.activityId(), a.unitPriceCents(), memberUnitPriceCents - a.unitPriceCents()));
    }

    /**
     * 结算/争议改单专用：先锁候选活动行再判限购，并把**本单自身**已核销的行排除在计数外。
     *
     * <p>限购是 check-then-act：两个会话并发结算时都能读到旧计数。行锁随外层结算事务
     * 提交释放，后到的事务在锁内重算时能看到先到方已提交的订单行，从而串行化。</p>
     */
    public Optional<Applied> resolveUnitPriceForOrder(Long userId, String deviceId, String orderId,
                                                      String skuId, int baseUnitPriceCents) {
        return resolveUnitPrice(userId, deviceId, resolveMerchantId(deviceId), skuId,
                baseUnitPriceCents, orderId, true);
    }

    private Optional<Applied> resolveUnitPrice(Long userId, String deviceId, String merchantId,
                                               String skuId, int baseUnitPriceCents,
                                               String excludeOrderId, boolean lockRows) {
        if (deviceId == null || skuId == null || baseUnitPriceCents <= MIN_PRICE_CENTS) {
            return Optional.empty();
        }
        List<PromotionActivity> candidates = activityRepository.findPricedCandidates(deviceId, merchantId, skuId);
        if (lockRows) {
            // 固定加锁顺序，避免多活动并发时交叉死锁
            candidates = candidates.stream()
                    .sorted(java.util.Comparator.comparing(PromotionActivity::getActivityId))
                    .toList();
        }
        Applied best = null;
        for (PromotionActivity candidate : candidates) {
            PromotionActivity a = candidate;
            if (lockRows) {
                a = activityRepository.findByIdForUpdate(a.getActivityId()).orElse(a);
            }
            Integer price = priceOf(a, baseUnitPriceCents);
            if (price == null || price >= baseUnitPriceCents || price < MIN_PRICE_CENTS) {
                continue;
            }
            if (!withinUsageLimits(a, userId, excludeOrderId)) {
                continue;
            }
            int discount = baseUnitPriceCents - price;
            if (best == null || discount > best.discountPerUnitCents()) {
                best = new Applied(a.getActivityId(), price, discount);
            }
        }
        return Optional.ofNullable(best);
    }

    public String resolveMerchantId(String deviceId) {
        if (deviceId == null) {
            return null;
        }
        return deviceRepository.findById(deviceId).map(DeviceInfo::getMerchantId).orElse(null);
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
    private boolean withinUsageLimits(PromotionActivity a, Long userId, String excludeOrderId) {
        if (a.getUserLimit() > 0 && userId != null
                && countOrders(a.getActivityId(), userId, excludeOrderId) >= a.getUserLimit()) {
            return false;
        }
        long totalLimit = readTotalOrderLimit(a);
        if (totalLimit > 0
                && countOrders(a.getActivityId(), null, excludeOrderId) >= totalLimit) {
            return false;
        }
        return true;
    }

    private long countOrders(Long activityId, Long userId, String excludeOrderId) {
        return excludeOrderId == null
                ? orderLineRepository.countOrdersForActivity(activityId, userId)
                : orderLineRepository.countOrdersForActivityExcluding(activityId, userId, excludeOrderId);
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
