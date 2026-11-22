package com.aicabinet.trade.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 消费者订阅模板 fail-closed（MA-384 复核）：两个 appid 的模板 id 不通用，
 * 消费者模板缺失时绝不回落商户模板——生产 example 里消费者模板默认为空，
 * 回落等于每次催付都拿商户模板发给消费者，微信回 400 却记成「已发送」。
 */
class WeChatMiniAppPropertiesConsumerTemplateTest {

    private static WeChatMiniAppProperties props(String consumerTemplate, String merchantTemplate) {
        return new WeChatMiniAppProperties(true, "app", "secret", merchantTemplate, "pages/alerts/alerts",
                consumerTemplate, "pages/messages/messages", "cApp", "cSecret", "mApp", "mSecret", "formal");
    }

    @Test
    void consumerTemplateMissingNeverFallsBackToMerchantTemplate() {
        WeChatMiniAppProperties p = props(null, "MERCHANT_TEMPLATE_ID");

        assertNull(p.resolveConsumerTemplateId(), "消费者模板空时必须返回 null，不能回落商户模板");
        assertFalse(p.hasConsumerTemplate());
    }

    @Test
    void blankConsumerTemplateAlsoFailsClosed() {
        WeChatMiniAppProperties p = props("   ", "MERCHANT_TEMPLATE_ID");

        assertNull(p.resolveConsumerTemplateId());
        assertFalse(p.hasConsumerTemplate());
    }

    @Test
    void configuredConsumerTemplateIsUsedVerbatim() {
        WeChatMiniAppProperties p = props("CONSUMER_TEMPLATE_ID", "MERCHANT_TEMPLATE_ID");

        assertEquals("CONSUMER_TEMPLATE_ID", p.resolveConsumerTemplateId());
        assertTrue(p.hasConsumerTemplate());
    }
}
