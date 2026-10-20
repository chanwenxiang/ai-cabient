package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 域名自钉（设计文档 §5「域名自钉已实装」，2026-10-19 拍板 A 第 1 条）。
 *
 * <p>锁三件事：① 照抄云侧 {@code status/msg=null} 的响应形态（不是 200 envelope）；
 * ② 基址归一化必须带 {@code /jiangyi/api} 尾段且幂等；③ 基址缺失时三端点一起 404，
 * 绝不允许「说有配置待拉」而拉取 404 的半开状态。</p>
 */
class DomainConfigControllerTest {

    private static final String IDENTIFIER = "CQYB10720";

    private MockHttpServletRequest authedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DeviceAuthInterceptor.ATTR_DEVICE_ID, "100000000001");
        request.setAttribute(DeviceAuthInterceptor.ATTR_IDENTIFIER, IDENTIFIER);
        return request;
    }

    private static DomainConfigController armed(String baseUrl) {
        return new DomainConfigController(baseUrl);
    }

    @Test
    void domainPullCopiesCloudNullEnvelopeAndAppendsApiTail() {
        DomainConfigController controller = armed("https://gw.example.com");
        Map<String, Object> resp = controller.getConfigDomain(authedRequest(),
                Map.of("identifier", IDENTIFIER, "doorNum", 0));
        assertNull(resp.get("status"), "云侧实测 status=null，不能规范成 200");
        assertNull(resp.get("msg"), "云侧实测 msg=null");
        assertEquals("https://gw.example.com/jiangyi/api", resp.get("data"));
    }

    @Test
    void domainPullIsIdempotentOnAlreadyNormalizedBase() {
        // 尾斜杠、已带 /jiangyi/api 两种配法都不能拼出双尾段
        assertEquals("https://gw.example.com/jiangyi/api",
                armed("https://gw.example.com/").getConfigDomain(authedRequest(), Map.of()).get("data"));
        assertEquals("https://gw.example.com/jiangyi/api",
                armed("https://gw.example.com/jiangyi/api")
                        .getConfigDomain(authedRequest(), Map.of()).get("data"));
        assertEquals("wss://gw.example.com/websocket/device/",
                armed("https://gw.example.com/jiangyi/api/")
                        .getConfigSocketUrl(authedRequest(), Map.of()).get("data"));
    }

    @Test
    void statusPullAlwaysAnswersOneForSelfHealing() {
        // 恒 1：最坏代价是每开机多一次幂等拉取，换来 COMMON_URL 被污染后能自己拉回来
        Map<String, Object> resp = armed("https://gw.example.com")
                .getConfigStatus(authedRequest(), Map.of("identifier", IDENTIFIER));
        assertNull(resp.get("status"));
        assertEquals(1, resp.get("data"));
    }

    @Test
    void socketPullCopiesCloudEnvelopeAndDerivesWsBaseFromSameHost() {
        // 云侧 2026-10-20 实测：{"status":null,"msg":null,"data":"wss://<host>/websocket/device/"}
        Map<String, Object> resp = armed("https://gw.example.com")
                .getConfigSocketUrl(authedRequest(), Map.of("identifier", IDENTIFIER));
        assertNull(resp.get("status"), "云侧实测 status=null，不能规范成 200");
        assertNull(resp.get("msg"));
        assertEquals("wss://gw.example.com/websocket/device/", resp.get("data"));
    }

    @Test
    void socketPullDowngradesSchemeForPlainHttpBase() {
        assertEquals("ws://gw.example.com/websocket/device/",
                armed("http://gw.example.com")
                        .getConfigSocketUrl(authedRequest(), Map.of()).get("data"));
    }

    @Test
    void allThreeEndpointsGoDarkTogetherWhenBaseMissing() {
        DomainConfigController controller = armed("");
        ResponseStatusException domain = assertThrows(ResponseStatusException.class,
                () -> controller.getConfigDomain(authedRequest(), Map.of()));
        ResponseStatusException status = assertThrows(ResponseStatusException.class,
                () -> controller.getConfigStatus(authedRequest(), Map.of()));
        ResponseStatusException socket = assertThrows(ResponseStatusException.class,
                () -> controller.getConfigSocketUrl(authedRequest(), Map.of()));
        assertEquals(404, domain.getStatusCode().value());
        assertEquals(404, status.getStatusCode().value());
        assertEquals(404, socket.getStatusCode().value());
    }

    @Test
    void refusesToPinAnUnparseableBase() {
        // 回错域名比不回域名破坏性大：非 http(s)/含空白的一律视为未配置
        for (String bad : new String[]{"gw.example.com", "ftp://gw.example.com", "https://a b.com"}) {
            DomainConfigController controller = armed(bad);
            assertThrows(ResponseStatusException.class,
                    () -> controller.getConfigDomain(authedRequest(), Map.of()), bad);
        }
    }

    @Test
    void toleratesBodyWithoutIdentifierAndStillPins() {
        // 真机 body 带 identifier；模拟器/后续固件可能不带——不带不影响下发
        Map<String, Object> resp = armed("https://gw.example.com")
                .getConfigDomain(authedRequest(), null);
        assertTrue(resp.containsKey("data"));
        assertEquals("https://gw.example.com/jiangyi/api", resp.get("data"));
    }
}
