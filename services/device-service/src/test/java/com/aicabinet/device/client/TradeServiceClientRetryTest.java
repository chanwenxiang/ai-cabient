package com.aicabinet.device.client;

import com.aicabinet.common.dto.DoorEventRequest;
import com.aicabinet.common.enums.DoorState;
import com.aicabinet.common.security.InternalApiProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Q4: notifyDoorEvent 最多 3 次退避重试；失败后由 listener 清 dedup。 */
class TradeServiceClientRetryTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/internal/v1/sessions/door-event", exchange -> {
            int n = hits.incrementAndGet();
            byte[] body = "{\"code\":0,\"message\":\"ok\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
            if (n < 3) {
                exchange.sendResponseHeaders(500, -1);
            } else {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void notifyDoorEvent_succeedsOnThirdAttempt() {
        TradeServiceClient client = new TradeServiceClient(baseUrl, new InternalApiProperties("test-key", null, null));
        DoorEventRequest req = new DoorEventRequest(
                "S-RETRY", "CAB-001", DoorState.CLOSED, System.currentTimeMillis(),
                null, null, null, null, null);

        assertDoesNotThrow(() -> client.notifyDoorEvent(req));
        assertEquals(3, hits.get());
    }

    @Test
    void notifyDoorEvent_exhaustedRetries_throws() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        hits.set(0);
        server.createContext("/internal/v1/sessions/door-event", exchange -> {
            hits.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        TradeServiceClient client = new TradeServiceClient(baseUrl, new InternalApiProperties("test-key", null, null));
        DoorEventRequest req = new DoorEventRequest(
                "S-FAIL", "CAB-001", DoorState.CLOSED, System.currentTimeMillis(),
                null, null, null, null, null);

        assertThrows(RuntimeException.class, () -> client.notifyDoorEvent(req));
        assertEquals(3, hits.get());
    }

    /** H54：openDoorFailed POST /internal/v1/sessions/{sessionId}/open-failed，带 internal key 与 reason。 */
    @Test
    void openDoorFailed_postsToInternalOpenFailedEndpoint() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        String[] captured = new String[2];
        server.createContext("/internal/v1/sessions/S-H54/open-failed", exchange -> {
            captured[0] = exchange.getRequestHeaders().getFirst("X-Internal-Api-Key");
            captured[1] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        TradeServiceClient client = new TradeServiceClient(baseUrl, new InternalApiProperties("test-key", null, null));
        client.openDoorFailed("S-H54", "开门指令确认超时");

        assertEquals("test-key", captured[0]);
        assertTrue(captured[1].contains("开门指令确认超时"));
    }

    /** H62a：notifyOpsAlert POST /internal/v1/ops-alerts，带 alertType/message/deviceId。 */
    @Test
    void notifyOpsAlert_postsToOpsAlertsEndpoint() throws IOException {
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress(0), 0);
        String[] captured = new String[2];
        server.createContext("/internal/v1/ops-alerts", exchange -> {
            captured[0] = exchange.getRequestHeaders().getFirst("X-Internal-Api-Key");
            captured[1] = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        TradeServiceClient client = new TradeServiceClient(baseUrl, new InternalApiProperties("test-key", null, null));
        client.notifyOpsAlert("EDGE_QUEUE_ABANDON", "queue abandoned", "CAB-001");

        assertEquals("test-key", captured[0]);
        assertTrue(captured[1].contains("EDGE_QUEUE_ABANDON"));
        assertTrue(captured[1].contains("CAB-001"));
    }
}
