package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.mapper.PromotionPlacementMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromotionActivityValidatorTest {

    @Mock private PromotionPlacementMapper placementRepository;
    private PromotionActivityValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PromotionActivityValidator(new ObjectMapper(), placementRepository);
    }

    private PromotionActivity activity(String type) {
        PromotionActivity a = new PromotionActivity();
        a.setActivityId(1L);
        a.setActivityType(type);
        a.setDeviceScope("ALL");
        a.setStartTime(Instant.now());
        a.setEndTime(Instant.now().plus(1, ChronoUnit.DAYS));
        return a;
    }

    @Test
    void skuReduce_withoutWindow_rejected() {
        PromotionActivity a = activity("SKU_REDUCE");
        a.setStartTime(null);
        a.setReduceCents(100);
        a.setRuleConfig("{\"skuIds\":[\"SKU-1\"]}");
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> validator.validateForSave(a));
        assertEquals(400, e.getStatusCode().value());
    }

    @Test
    void skuReduce_withoutSku_rejected() {
        PromotionActivity a = activity("SKU_REDUCE");
        a.setReduceCents(100);
        a.setRuleConfig("{}");
        assertThrows(ResponseStatusException.class, () -> validator.validateForSave(a));
    }

    @Test
    void skuReduce_valid_passes() {
        PromotionActivity a = activity("SKU_REDUCE");
        a.setReduceCents(100);
        a.setRuleConfig("{\"skuIds\":[\"SKU-1\"],\"totalOrderLimit\":100}");
        assertDoesNotThrow(() -> validator.validateForSave(a));
    }

    @Test
    void skuDiscount_zeroBps_rejected() {
        PromotionActivity a = activity("SKU_DISCOUNT");
        a.setDiscountBps(0);
        a.setRuleConfig("{\"skuIds\":[\"SKU-1\"]}");
        assertThrows(ResponseStatusException.class, () -> validator.validateForSave(a));
    }

    @Test
    void skuFixedPrice_zero_rejected_noFreeOrder() {
        PromotionActivity a = activity("SKU_FIXED_PRICE");
        a.setFixedPriceCents(0);
        a.setRuleConfig("{\"skuIds\":[\"SKU-1\"]}");
        assertThrows(ResponseStatusException.class, () -> validator.validateForSave(a));
    }

    @Test
    void merchantScope_requiresMerchantIds() {
        PromotionActivity a = activity("SKU_REDUCE");
        a.setReduceCents(100);
        a.setDeviceScope("MERCHANT");
        a.setRuleConfig("{\"skuIds\":[\"SKU-1\"]}");
        assertThrows(ResponseStatusException.class, () -> validator.validateForSave(a));

        a.setRuleConfig("{\"skuIds\":[\"SKU-1\"],\"merchantIds\":[\"M-1\"]}");
        assertDoesNotThrow(() -> validator.validateForSave(a));
    }

    @Test
    void merchantScope_notAllowedForLegacyCouponType() {
        PromotionActivity a = activity("FULL_REDUCE");
        a.setDeviceScope("MERCHANT");
        a.setRuleConfig("{\"merchantIds\":[\"M-1\"]}");
        assertThrows(ResponseStatusException.class, () -> validator.validateForSave(a));
    }

    @Test
    void legacyType_doesNotRequireWindow() {
        PromotionActivity a = activity("FULL_REDUCE");
        a.setStartTime(null);
        a.setEndTime(null);
        assertDoesNotThrow(() -> validator.validateForSave(a));
    }

    @Test
    void launch_skuActivity_withoutBanner_rejected() {
        PromotionActivity a = activity("SKU_REDUCE");
        a.setReduceCents(100);
        a.setRuleConfig("{\"skuIds\":[\"SKU-1\"]}");
        when(placementRepository.countByActivityIdAndPosition(1L, "BANNER")).thenReturn(0L);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> validator.validateForLaunch(a));
        assertEquals(409, e.getStatusCode().value());
    }

    @Test
    void launch_skuActivity_withBanner_passes() {
        PromotionActivity a = activity("SKU_REDUCE");
        a.setReduceCents(100);
        a.setRuleConfig("{\"skuIds\":[\"SKU-1\"]}");
        when(placementRepository.countByActivityIdAndPosition(1L, "BANNER")).thenReturn(1L);
        assertDoesNotThrow(() -> validator.validateForLaunch(a));
    }

    // ── MA-085：类型闭集（权威源 = 字典 promotion_type，种子见 V180）──────────────────
    @Test
    void knownDictionaryTypes_stillPassValidation() {
        // 库里实测在用的四个非/半 SKU 类型不能被自己的白名单拦死
        for (String type : new String[] { "DISCOUNT", "NEW_USER", "POINTS", "FULL_REDUCE" }) {
            final String t = type;
            org.junit.jupiter.api.Assertions.assertDoesNotThrow(
                    () -> validator.validateForSave(activity(t)), t);
        }
    }

    @Test
    void couponTypeUsedAsActivityType_rejectedBecauseItWouldLaunchButNeverPrice() {
        // AMOUNT_OFF 是券类型（CouponService:355），不是活动类型：
        // 原来它落库后既不走 SKU 定价也不被任何 switch 命中，却能 launch 成 ACTIVE ⇒ 活动在线但不生效
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> validator.validateForSave(activity("AMOUNT_OFF")));
        assertEquals(400, e.getStatusCode().value());
        org.junit.jupiter.api.Assertions.assertTrue(e.getReason().contains("promotion_type"), e.getReason());
    }

    @Test
    void blankActivityType_rejected() {
        assertThrows(ResponseStatusException.class, () -> validator.validateForSave(activity(null)));
        assertThrows(ResponseStatusException.class, () -> validator.validateForSave(activity("   ")));
    }
}
