package com.aicabinet.trade.wechat;

import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 小程序码（getwxacodeunlimit）的未配置分支。
 *
 * <p>为什么先钉这一条：柜门贴码改小程序码后，失败路径**必须**返回 empty 让调用方 503，
 * 而不是静默降级回普通链接二维码——静默降级正是 2026-11-19 那个缺陷的成因
 * （运营以为贴的是能扫的码，实际扫了只落到 302 短链）。
 *
 * <p>只用真实 record 构造配置，不 mock 配置类：mock 一个 record 的 isConfigured
 * 会把「配置判据本身」也一起假掉。
 */
class WeChatMiniAppClientWxaCodeTest {

    private static final String PAGE = "pages/index/index";
    private static final String SCENE = "EG858564255060";

    private static WeChatMiniAppClient client(boolean enabled, String appId, String appSecret) {
        WeChatMiniAppProperties props = new WeChatMiniAppProperties(
                enabled,
                "",
                "",
                "",
                "",
                "",
                "",
                appId,
                appSecret,
                "",
                "",
                "formal");
        // SecurityProperties 在构造器里只被赋值；本类用例走不到 mock 分支
        return new WeChatMiniAppClient(props, new SecurityProperties(false), new ObjectMapper());
    }

    /** 消费者端没配齐 ⇒ 直接空，绝不发请求（否则会退化成「看着成功其实没码」）。 */
    @Test
    void notConfigured_returnsEmpty() {
        WeChatMiniAppClient c = client(false, "wx-consumer", "secret");
        assertTrue(c.getWxaCodeUnlimited(SCENE, PAGE, "release", MiniAppTarget.CONSUMER).isEmpty());
    }

    /** 两端 appid 隔离：没有消费者凭据就出不了消费者码（串端会被微信 40029 拒）。 */
    @Test
    void consumerCredentialsMissing_returnsEmpty() {
        WeChatMiniAppClient c = client(true, "", "");
        assertTrue(c.getWxaCodeUnlimited(SCENE, PAGE, "release", MiniAppTarget.CONSUMER).isEmpty());
    }
}
