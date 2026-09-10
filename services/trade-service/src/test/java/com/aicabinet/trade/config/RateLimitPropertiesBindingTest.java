package com.aicabinet.trade.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防止 RateLimitProperties record 多构造器/缺 @DefaultValue 导致
 * {@code @ConfigurationProperties} 启动绑失败（曾在 ba94a41e 引入过）。
 */
class RateLimitPropertiesBindingTest {

    @Test
    void bindsAllFieldsFromRelaxedNames() {
        Map<String, Object> source = new HashMap<>();
        source.put("aicabinet.rate-limit.enabled", "false");
        source.put("aicabinet.rate-limit.max-coupon-claims-per-hour", "11");
        source.put("aicabinet.rate-limit.max-order-pays-per-hour", "12");
        source.put("aicabinet.rate-limit.max-session-creates-per-hour", "13");
        source.put("aicabinet.rate-limit.max-open-doors-per-hour", "14");
        source.put("aicabinet.rate-limit.max-open-doors-per-device-per-hour", "15");

        RateLimitProperties props = bind(source);

        assertFalse(props.enabled());
        assertEquals(11, props.maxCouponClaimsPerHour());
        assertEquals(12, props.maxOrderPaysPerHour());
        assertEquals(13, props.maxSessionCreatesPerHour());
        assertEquals(14, props.maxOpenDoorsPerHour());
        assertEquals(15, props.maxOpenDoorsPerDevicePerHour());
    }

    @Test
    void bindsDefaultsWhenOnlyPartialKeysPresent() {
        // 旧版曾用 3 参重载构造兼容「只配券/支付」；绑定必须仍能落到单构造器 + @DefaultValue
        Map<String, Object> source = Map.of(
                "aicabinet.rate-limit.max-coupon-claims-per-hour", "5",
                "aicabinet.rate-limit.max-order-pays-per-hour", "6"
        );

        RateLimitProperties props = bind(source);

        assertTrue(props.enabled());
        assertEquals(5, props.maxCouponClaimsPerHour());
        assertEquals(6, props.maxOrderPaysPerHour());
        assertEquals(20, props.maxSessionCreatesPerHour());
        assertEquals(30, props.maxOpenDoorsPerHour());
        assertEquals(60, props.maxOpenDoorsPerDevicePerHour());
    }

    @Test
    void bindsDefaultsWhenOnlyEnabledPresent() {
        Map<String, Object> source = Map.of("aicabinet.rate-limit.enabled", "true");

        RateLimitProperties props = bind(source);

        assertTrue(props.enabled());
        assertEquals(20, props.maxCouponClaimsPerHour());
        assertEquals(30, props.maxOrderPaysPerHour());
        assertEquals(20, props.maxSessionCreatesPerHour());
        assertEquals(30, props.maxOpenDoorsPerHour());
        assertEquals(60, props.maxOpenDoorsPerDevicePerHour());
    }

    @Test
    void compactConstructorClampsNonPositiveToDefaults() {
        RateLimitProperties props = new RateLimitProperties(true, 0, -1, 0, 0, 0);

        assertEquals(20, props.maxCouponClaimsPerHour());
        assertEquals(30, props.maxOrderPaysPerHour());
        assertEquals(20, props.maxSessionCreatesPerHour());
        assertEquals(30, props.maxOpenDoorsPerHour());
        assertEquals(60, props.maxOpenDoorsPerDevicePerHour());
    }

    private static RateLimitProperties bind(Map<String, ?> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("aicabinet.rate-limit", Bindable.of(RateLimitProperties.class))
                .orElseThrow(() -> new AssertionError("RateLimitProperties failed to bind"));
    }
}
