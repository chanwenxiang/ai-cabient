package com.aicabinet.trade.client;

import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiStdSku;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：login2 必须以无鉴权方式发出（withAuth=false）。历史缺陷：bearerToken() 调 post()，
 * post() 又调 bearerToken()，首调即无限递归 → StackOverflowError → 接口 500
 * （2026-10-21 追踪号 3b6fec1ee3c1）。
 */
class JiangyiGatherClientTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicInteger loginCalls = new AtomicInteger();
    private static volatile String loginAuthorization;
    private static volatile String listAuthorization;
    private static volatile String gatherCheckBody;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/merchant/authentication/login2", exchange -> {
            loginCalls.incrementAndGet();
            loginAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, "{\"status\":200,\"msg\":\"\",\"data\":\"token-abc\"}");
        });
        server.createContext("/merchant/stdSku/getList", exchange -> {
            listAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
            respond(exchange, "{\"status\":200,\"msg\":\"\",\"data\":[{\"id\":100013,"
                    + "\"name\":\"330ml雪碧柠檬味汽水\",\"barCode\":\"6901234567890\","
                    + "\"barCodeSource\":\"app\"}]}");
        });
        server.createContext("/merchant/selfhelpGatherProduct/gatherCheck", exchange -> {
            gatherCheckBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(exchange, "{\"status\":200,\"msg\":\"\",\"data\":null}");
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, String json) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(200, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void loginIsSentWithoutAuthAndTokenIsReused() {
        JiangyiGatherClient client = new JiangyiGatherClient(baseUrl, "13800000000", "secret", 21);
        int loginCallsBefore = loginCalls.get();

        List<JiangyiStdSku> first = client.stdSkuList("雪碧", null);
        assertEquals(1, first.size());
        assertEquals(100013L, first.get(0).id());
        assertEquals("330ml雪碧柠檬味汽水", first.get(0).name());
        assertEquals("app", first.get(0).barCodeSource());
        assertNull(loginAuthorization, "login2 不能携带 Bearer，否则 bearerToken/post 递归");
        assertEquals("Bearer token-abc", listAuthorization);

        client.stdSkuList("雪碧", null);
        assertEquals(1, loginCalls.get() - loginCallsBefore, "25 分钟内应复用缓存 token，不重复登录");
    }

    /** 回归：gatherCheck 的「全部」= productIds:null；空数组会让厂商 SQL in () 语法报错 555。 */
    @Test
    void gatherCheckSendsNullProductIdsForAll() {
        JiangyiGatherClient client = new JiangyiGatherClient(baseUrl, "13800000000", "secret", 21);

        assertTrue(client.gatherCheck(null).isEmpty());
        assertEquals("{\"productIds\":null}", gatherCheckBody);

        client.gatherCheck(List.of());
        assertEquals("{\"productIds\":null}", gatherCheckBody, "空列表同样不能发 []");

        client.gatherCheck(List.of(100013L));
        assertEquals("{\"productIds\":[100013]}", gatherCheckBody);
    }
}
