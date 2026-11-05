package com.aicabinet.trade.client;

import com.aicabinet.trade.dto.JiangyiRestockDtos.ProductState;
import com.aicabinet.trade.dto.JiangyiRestockDtos.State;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockLine;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockProgress;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockSubmitCommand;
import com.aicabinet.trade.dto.JiangyiRestockDtos.RestockSubmitResult;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 第七章客户端契约钉测（文档 V16.0.0 2026-10-24 §7.5~§7.19）。
 *
 * <p>钉的是「我方出向形状」不是厂商行为：路径前缀、Bearer、金额单位（元）、
 * requestId 透传、错误 msg 原文保留、401 重登一次。厂商真实语义待 `_ID-OK` 柜到位后联调。</p>
 */
class JiangyiRestockClientTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final AtomicInteger loginCalls = new AtomicInteger();
    private static volatile String lastLoginAuth;
    private static volatile String lastPath;
    private static volatile String lastBody;
    private static volatile String lastAuth;
    private static volatile String lastContentType;
    private static volatile String nextResponse;
    private static volatile int nextHttpStatus;
    private static final java.util.concurrent.atomic.AtomicBoolean failNextCall = new java.util.concurrent.atomic.AtomicBoolean();

    private JiangyiRestockClient client;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            lastPath = path;
            lastAuth = exchange.getRequestHeaders().getFirst("Authorization");
            lastContentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if (path.equals("/merchant/authentication/login2")) {
                loginCalls.incrementAndGet();
                lastLoginAuth = exchange.getRequestHeaders().getFirst("Authorization");
                respond(exchange, 200, "{\"status\":200,\"msg\":\"\",\"data\":\"token-abc\"}");
                return;
            }
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (failNextCall.getAndSet(false)) {
                respond(exchange, 401, "{\"msg\":\"unauthorized\"}");
                return;
            }
            int code = nextHttpStatus == 0 ? 200 : nextHttpStatus;
            respond(exchange, code, nextResponse);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        server.stop(0);
    }

    @BeforeEach
    void setUp() {
        loginCalls.set(0);
        lastBody = null;
        lastAuth = null;
        nextHttpStatus = 0;
        failNextCall.set(false);
        client = new JiangyiRestockClient(
                new JiangyiMerchantTokenProvider(baseUrl, "13800000000", "pw"), baseUrl);
    }

    @Test
    void lookupParsesStateMachineFields() {
        nextResponse = "{\"status\":200,\"msg\":\"成功\",\"data\":{"
                + "\"barCode\":\"4710088410132\",\"state\":\"CABINET_PENDING\",\"productId\":39788,"
                + "\"name\":\"黑松沙士\",\"nextAction\":\"RESTOCK_THEN_CABINET_CAPTURE\"}}";

        ProductState state = client.lookup("4710088410132");

        assertEquals("/merchant/openapi/v2/restock/product/lookup", lastPath);
        assertEquals("Bearer token-abc", lastAuth);
        assertEquals(State.CABINET_PENDING, state.state());
        assertEquals(39788L, state.productId());
        assertEquals("RESTOCK_THEN_CABINET_CAPTURE", state.nextAction());
        assertTrue(lastBody.contains("\"barCode\":\"4710088410132\""));
    }

    @Test
    void unknownStateDoesNotThrow() {
        nextResponse = "{\"status\":200,\"msg\":\"\",\"data\":{\"barCode\":\"1\",\"state\":\"SOMETHING_NEW\"}}";
        assertEquals(State.UNKNOWN, client.lookup("1").state());
    }

    @Test
    void createSendsSalePriceInYuan() {
        nextResponse = "{\"status\":200,\"msg\":\"\",\"data\":{\"barCode\":\"1\",\"state\":\"NEED_PHONE\","
                + "\"productId\":10}}";

        client.create(new com.aicabinet.trade.dto.JiangyiRestockDtos.ProductCreateCommand(
                "1", 2500L, null, "可乐", "可口可乐", "330ml", "bottle", null, null, null));

        assertEquals("/merchant/openapi/v2/restock/product/create", lastPath);
        assertTrue(lastBody.contains("\"salePrice\":25.00"), "salePrice 必须是元: " + lastBody);
        assertFalse(lastBody.contains("purchasePrice"), "未传进货价时不应出现该键");
    }

    @Test
    void batchStatusUsesStatusEndpointNotLookup() {
        nextResponse = "{\"status\":200,\"msg\":\"\",\"data\":["
                + "{\"barCode\":\"1\",\"state\":\"READY\",\"productId\":7},"
                + "{\"barCode\":\"2\",\"state\":\"REVIEWING\"}]}";

        List<ProductState> states = client.batchStatus(List.of("1", "2"));

        assertEquals("/merchant/openapi/v2/restock/product/status", lastPath);
        assertEquals(2, states.size());
        assertEquals(State.READY, states.get(0).state());
        assertNull(states.get(1).productId());
    }

    @Test
    void submitRestockSendsFinalStockInYuanWithoutOrientationForSingleDoor() {
        nextResponse = "{\"status\":200,\"msg\":\"成功\",\"data\":{\"historyNo\":\"B261024000123\","
                + "\"cabinetCaptureProducts\":[{\"productId\":39788,\"name\":\"黑松沙士\","
                + "\"barCode\":\"4710088410132\",\"onShelf\":true}]}}";

        RestockSubmitResult result = client.submitRestock(new RestockSubmitCommand(
                "r-20261105-001", "CQYB11246", 1, null, null,
                List.of(new RestockLine(null, "4710088410132", 6, 3050L))));

        assertEquals("/merchant/openapi/v2/restock/restock/submit", lastPath);
        assertTrue(lastBody.contains("\"requestId\":\"r-20261105-001\""));
        assertTrue(lastBody.contains("\"floorNum\":1"));
        assertTrue(lastBody.contains("\"salePrice\":30.50"), "salePrice 必须是元: " + lastBody);
        assertFalse(lastBody.contains("floorOrientation"), "单门柜不传（§7.11）");
        assertEquals("B261024000123", result.historyNo());
        assertEquals(1, result.cabinetCaptureProducts().size());
        assertTrue(result.cabinetCaptureProducts().get(0).onShelf());
    }

    @Test
    void restockLineRequiresExactlyOneIdentifierAndPositivePrice() {
        RestockSubmitCommand noId = new RestockSubmitCommand("r1", "CQYB1", 1, null, null,
                List.of(new RestockLine(null, null, 1, 100L)));
        assertThrows(IllegalArgumentException.class, () -> client.submitRestock(noId));

        RestockSubmitCommand both = new RestockSubmitCommand("r1", "CQYB1", 1, null, null,
                List.of(new RestockLine(1L, "123", 1, 100L)));
        assertThrows(IllegalArgumentException.class, () -> client.submitRestock(both));

        RestockSubmitCommand zeroPrice = new RestockSubmitCommand("r1", "CQYB1", 1, null, null,
                List.of(new RestockLine(null, "123", 1, 0L)));
        assertThrows(IllegalArgumentException.class, () -> client.submitRestock(zeroPrice));
    }

    @Test
    void progressParsesReadyAndPending() {
        nextResponse = "{\"status\":200,\"msg\":\"\",\"data\":{\"ready\":false,\"stage\":\"BUILDING\","
                + "\"total\":2,\"applied\":1,\"pendingNames\":[\"黑松沙士\"],\"pendingCodes\":[\"4710\"]}}";

        RestockProgress progress = client.restockProgress("CQYB11246", null);

        assertEquals("/merchant/openapi/v2/restock/restock/progress", lastPath);
        assertFalse(progress.ready());
        assertEquals("BUILDING", progress.stage());
        assertEquals(1, progress.pendingNames().size());
        assertFalse(lastBody.contains("historyNo"), "historyNo 为空时不传（§7.12 O）");
    }

    @Test
    void vendorErrorKeepsMsgVerbatim() {
        nextResponse = "{\"status\":555,\"msg\":\"商品「黑松沙士」的识别模板尚未生成，请稍后重试\"}";

        JiangyiRestockException e = assertThrows(JiangyiRestockException.class,
                () -> client.submitRestock(new RestockSubmitCommand("r1", "CQYB1", 1, null, null,
                        List.of(new RestockLine(null, "123", 1, 100L)))));

        assertEquals(555, e.status());
        assertEquals("商品「黑松沙士」的识别模板尚未生成，请稍后重试", e.vendorMsg());
    }

    @Test
    void http401RefreshesTokenAndRetriesOnce() {
        nextResponse = "{\"status\":200,\"msg\":\"\",\"data\":{\"barCode\":\"1\",\"state\":\"NEW\"}}";
        failNextCall.set(true);

        ProductState state = client.lookup("1");

        assertEquals(2, loginCalls.get(), "401 后应失效重登一次");
        assertEquals(State.NEW, state.state());
    }

    @Test
    void uploadImagePostsMultipartAndReturnsVendorUrl() {
        nextResponse = "{\"status\":200,\"msg\":\"\",\"data\":{\"url\":\"https://oss/x.jpg\"}}";

        String url = client.uploadImage("abc".getBytes(StandardCharsets.UTF_8), "a.jpg", "image/jpeg");

        assertEquals("/merchant/openapi/v2/restock/image/upload", lastPath);
        assertTrue(lastContentType.startsWith("multipart/form-data"), lastContentType);
        assertTrue(lastBody.contains("name=\"file\""), lastBody);
        assertEquals("https://oss/x.jpg", url);
    }

    @Test
    void uploadRejectsNonJpgPng() {
        assertThrows(IllegalArgumentException.class,
                () -> client.uploadImage("abc".getBytes(StandardCharsets.UTF_8), "a.gif", "image/gif"));
    }

    private static void respond(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
