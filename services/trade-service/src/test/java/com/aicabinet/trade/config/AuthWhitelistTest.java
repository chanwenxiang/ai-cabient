package com.aicabinet.trade.config;

import com.aicabinet.trade.api.AlipayNotifyController;
import com.aicabinet.trade.api.PayScoreNotifyController;
import com.aicabinet.trade.api.WeChatNotifyController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渠道回调必须免登录：回调方没有用户 JWT，只靠自身签名验签。
 * 这里用控制器上的真实映射反查白名单，防止新增回调端点时漏登记（P2-8 的同型缺陷）。
 */
class AuthWhitelistTest {

    @Test
    @DisplayName("微信支付/退款/支付宝/支付分回调均在免登录白名单")
    void channelCallbacks_areWhitelisted() {
        assertMapped(WeChatNotifyController.class, "/notify");
        assertMapped(WeChatNotifyController.class, "/refund-notify");
        assertMapped(AlipayNotifyController.class, "/notify");
        assertMapped(PayScoreNotifyController.class, "/notify");
    }

    private static void assertMapped(Class<?> controller, String methodPath) {
        RequestMapping base = controller.getAnnotation(RequestMapping.class);
        String prefix = base == null || base.value().length == 0 ? "" : base.value()[0];
        String path = prefix + methodPath;
        assertTrue(WebConfig.AUTH_EXCLUDE_PATTERNS.contains(path),
                "回调端点缺少免登录白名单：" + path + "（controller=" + controller.getSimpleName() + "）");
    }
}
