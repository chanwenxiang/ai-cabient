package com.aicabinet.trade.wechat;

import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 订阅消息请求体（{@code subscribe/send}）的落地版本与落地页口径。
 *
 * <p>钉的是 MA-372：{@code miniprogram_state} 曾在客户端写死 {@code "developer"}，而该项决定
 * 「用户点开消息落到哪个版本的小程序」——开发版对普通微信用户是不可打开的，消息等于白发。
 * 这里直接断言请求体取值，避免再用「读源码 grep」这种会被格式化/重构骗过的方式验收。
 */
class WeChatMiniAppClientSubscribeBodyTest {

    private static final String OPEN_ID = "oX-abc";
    private static final String TEMPLATE = "tpl-consumer";

    private static WeChatMiniAppClient clientWithState(String miniprogramState) {
        WeChatMiniAppProperties props = new WeChatMiniAppProperties(
                true,
                "",
                "",
                TEMPLATE,
                "pages/alerts/alerts",
                TEMPLATE,
                "pages/messages/messages",
                "wx-consumer",
                "secret",
                "wx-merchant",
                "secret",
                miniprogramState);
        return new WeChatMiniAppClient(props, new SecurityProperties(false), new ObjectMapper());
    }

    private static Map<String, String> fields() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("thing1", "订单待支付");
        data.put("thing2", "您有一笔订单 15 分钟后关闭");
        return data;
    }

    private static Map<String, Object> consumerBody(String miniprogramState) {
        return clientWithState(miniprogramState)
                .buildSubscribeBody(OPEN_ID, TEMPLATE, null, fields(), MiniAppTarget.CONSUMER);
    }

    /** 未配置 ⇒ formal（线上版）。绝不能默认 developer：这是缺陷本体。 */
    @Test
    void blankState_defaultsToFormal_notDeveloper() {
        Map<String, Object> body = consumerBody("");

        assertEquals("formal", body.get("miniprogram_state"));
        assertNotEquals("developer", body.get("miniprogram_state"));
    }

    /** 体验版验收期可切 trial（否则消息会把测试者带到线上旧版本）。 */
    @Test
    void trialState_isHonored() {
        assertEquals("trial", consumerBody("trial").get("miniprogram_state"));
    }

    /** 大小写/空白归一：配置里写 Trial 也能生效，不必因书写差异静默回落。 */
    @Test
    void stateIsCaseInsensitive() {
        assertEquals("trial", consumerBody("  TRIAL ").get("miniprogram_state"));
    }

    /** 非法值回落 formal：宁可跳线上版，也不能跳用户打不开的版本。 */
    @Test
    void unknownState_fallsBackToFormal() {
        assertEquals("formal", consumerBody("production").get("miniprogram_state"));
    }

    /** page 缺省按 target 分端回落：消费者消息落消费者页，不是商户告警页。 */
    @Test
    void blankPage_fallsBackByTarget() {
        WeChatMiniAppClient client = clientWithState("formal");

        assertEquals("pages/messages/messages",
                client.buildSubscribeBody(OPEN_ID, TEMPLATE, null, fields(), MiniAppTarget.CONSUMER).get("page"));
        assertEquals("pages/alerts/alerts",
                client.buildSubscribeBody(OPEN_ID, TEMPLATE, null, fields(), MiniAppTarget.MERCHANT).get("page"));
    }

    /** 显式 page 优先；data 值按微信 20 字上限截断。 */
    @Test
    void explicitPageAndDataTruncation() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("thing1", "标题超过二十个字符时会被微信拒绝所以必须截断这句话很长");
        Map<String, Object> body = clientWithState("formal")
                .buildSubscribeBody(OPEN_ID, TEMPLATE, "pages/mine/mine", data, MiniAppTarget.CONSUMER);

        assertEquals("pages/mine/mine", body.get("page"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, String>> sent = (Map<String, Map<String, String>>) body.get("data");
        assertEquals(20, sent.get("thing1").get("value").length());
    }
}
