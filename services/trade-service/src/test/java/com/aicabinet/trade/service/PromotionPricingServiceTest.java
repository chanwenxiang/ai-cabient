package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.mapper.CabinetOrderLineMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.PromotionActivityMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 活动定价 v1（CB-082）的金额规则钉测：
 * 立减 / 折扣（向下取整）/ 一口价（≥原价不生效）/ 最低 1 分（不允许 0 元）/
 * 每人限次 / 活动总单数 / 多个活动取最优。
 */
@ExtendWith(MockitoExtension.class)
class PromotionPricingServiceTest {

    private static final String DEVICE = "EG943322621391";
    private static final String MERCHANT = "MCH-DEFAULT";
    private static final String SKU = "100013";

    @Mock private PromotionActivityMapper activityRepository;
    @Mock private CabinetOrderLineMapper orderLineRepository;
    @Mock private DeviceInfoMapper deviceRepository;

    private PromotionPricingService service;

    @BeforeEach
    void setUp() {
        service = new PromotionPricingService(activityRepository, orderLineRepository, deviceRepository,
                new ObjectMapper());
        DeviceInfo device = new DeviceInfo();
        device.setDeviceId(DEVICE);
        device.setMerchantId(MERCHANT);
        lenient().when(deviceRepository.findById(DEVICE)).thenReturn(Optional.of(device));
    }

    private PromotionActivity activity(long id, String type, Integer reduce, Integer bps, Integer fixed,
                                       int userLimit, String ruleConfig) {
        PromotionActivity a = new PromotionActivity();
        a.setActivityId(id);
        a.setActivityType(type);
        a.setReduceCents(reduce);
        a.setDiscountBps(bps);
        a.setFixedPriceCents(fixed);
        a.setUserLimit(userLimit);
        a.setRuleConfig(ruleConfig == null ? "{}" : ruleConfig);
        return a;
    }

    private void candidates(PromotionActivity... list) {
        when(activityRepository.findPricedCandidates(eq(DEVICE), eq(MERCHANT), eq(SKU)))
                .thenReturn(List.of(list));
    }

    @Test
    @DisplayName("商品立减：1000 − 150 = 850，优惠 150 分")
    void skuReduce_appliesOff() {
        candidates(activity(1, "SKU_REDUCE", 150, null, null, 0, null));
        var applied = service.resolveUnitPrice(10001L, DEVICE, SKU, 1000).orElseThrow();
        assertEquals(850, applied.unitPriceCents());
        assertEquals(150, applied.discountPerUnitCents());
    }

    @Test
    @DisplayName("商品折扣：999 @ 85 折 ⇒ 849（向下取整），优惠 150 分")
    void skuDiscount_floorsPrice() {
        candidates(activity(2, "SKU_DISCOUNT", null, 1500, null, 0, null));
        var applied = service.resolveUnitPrice(10001L, DEVICE, SKU, 999).orElseThrow();
        assertEquals(849, applied.unitPriceCents());
        assertEquals(150, applied.discountPerUnitCents());
    }

    @Test
    @DisplayName("一口价：低于原价生效；≥原价不生效")
    void skuFixedPrice_onlyWhenCheaper() {
        candidates(activity(3, "SKU_FIXED_PRICE", null, null, 800, 0, null));
        assertEquals(800, service.resolveUnitPrice(10001L, DEVICE, SKU, 1000).orElseThrow().unitPriceCents());

        candidates(activity(4, "SKU_FIXED_PRICE", null, null, 1200, 0, null));
        assertTrue(service.resolveUnitPrice(10001L, DEVICE, SKU, 1000).isEmpty());
    }

    @Test
    @DisplayName("立减超过原价：最低 1 分，不允许 0 元")
    void skuReduce_neverBelowOneCent() {
        candidates(activity(5, "SKU_REDUCE", 500, null, null, 0, null));
        var applied = service.resolveUnitPrice(10001L, DEVICE, SKU, 100).orElseThrow();
        assertEquals(1, applied.unitPriceCents());
    }

    @Test
    @DisplayName("每人限次：已达上限的活动跳过")
    void userLimit_reached_skips() {
        candidates(activity(6, "SKU_REDUCE", 100, null, null, 1, null));
        when(orderLineRepository.countOrdersForActivity(6L, 10001L)).thenReturn(1L);
        assertTrue(service.resolveUnitPrice(10001L, DEVICE, SKU, 1000).isEmpty());
    }

    @Test
    @DisplayName("活动总单数：rule_config.totalOrderLimit 达上限跳过")
    void totalOrderLimit_reached_skips() {
        candidates(activity(7, "SKU_REDUCE", 100, null, null, 0, "{\"totalOrderLimit\":2}"));
        when(orderLineRepository.countOrdersForActivity(7L, null)).thenReturn(2L);
        assertTrue(service.resolveUnitPrice(10001L, DEVICE, SKU, 1000).isEmpty());
    }

    @Test
    @DisplayName("多个活动命中：取优惠更大的")
    void multipleActivities_pickBest() {
        candidates(
                activity(8, "SKU_REDUCE", 100, null, null, 0, null),
                activity(9, "SKU_DISCOUNT", null, 2000, null, 0, null));
        var applied = service.resolveUnitPrice(10001L, DEVICE, SKU, 1000).orElseThrow();
        assertEquals(9L, applied.activityId());
        assertEquals(800, applied.unitPriceCents());
    }

    @Test
    @DisplayName("无候选活动：empty（不改变原价）")
    void noCandidates_empty() {
        when(activityRepository.findPricedCandidates(any(), any(), any())).thenReturn(List.of());
        assertTrue(service.resolveUnitPrice(10001L, DEVICE, SKU, 1000).isEmpty());
    }

    @Test
    @DisplayName("未知类型：跳过（不猜价格）")
    void unknownType_skips() {
        candidates(activity(10, "SECOND_HALF", null, null, null, 0, null));
        assertTrue(service.resolveUnitPrice(10001L, DEVICE, SKU, 1000).isEmpty());
        assertEquals(0L, countCalls());
    }

    @Test
    @DisplayName("结算链：锁活动行，并排除本单后再判限购")
    void forOrder_locksActivityRow_andExcludesCurrentOrder() {
        PromotionActivity a = activity(6, "SKU_REDUCE", 100, null, null, 1, null);
        candidates(a);
        when(activityRepository.findByIdForUpdate(6L)).thenReturn(Optional.of(a));
        // 本单已带 activity_id；若把自己算进去，user_limit=1 会误判超限
        when(orderLineRepository.countOrdersForActivityExcluding(6L, 10001L, "O-CUR")).thenReturn(0L);

        var applied = service.resolveUnitPriceForOrder(10001L, DEVICE, "O-CUR", SKU, 1000).orElseThrow();

        assertEquals(900, applied.unitPriceCents());
        verify(activityRepository).findByIdForUpdate(6L);
        verify(orderLineRepository).countOrdersForActivityExcluding(6L, 10001L, "O-CUR");
    }

    private long countCalls() {
        return org.mockito.Mockito.mockingDetails(orderLineRepository).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("countOrdersForActivity")).count();
    }
}
