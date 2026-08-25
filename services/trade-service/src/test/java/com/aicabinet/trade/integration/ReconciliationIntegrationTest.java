package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.PaymentOperation;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.PaymentOperationMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.service.ReconciliationService;
import com.aicabinet.common.enums.SessionState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class ReconciliationIntegrationTest {

    @Container
    @SuppressWarnings("resource") // lifecycle owned by Testcontainers JUnit extension
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource") // lifecycle owned by Testcontainers JUnit extension
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private CabinetOrderMapper orderRepository;

    @Autowired
    private ShoppingSessionMapper sessionRepository;

    @Autowired
    private PaymentOperationMapper paymentOperationRepository;

    @Test
    void mockReconciliation_matchesInsertedOrder() {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        Instant now = Instant.now();
        ShoppingSession session = new ShoppingSession();
        session.setSessionId("IT-SES-1");
        session.setUserId(10001L);
        session.setDeviceId("CAB-001");
        session.setState(SessionState.COMPLETED);
        session.setIdempotencyKey("it-reconciliation-session");
        sessionRepository.save(session);

        CabinetOrder order = new CabinetOrder();
        order.setOrderId("IT-ORD-" + System.currentTimeMillis());
        order.setSessionId("IT-SES-1");
        order.setUserId(10001L);
        order.setDeviceId("CAB-001");
        order.setTotalAmountCents(350);
        order.setStatus("PAID");
        order.setCreatedAt(now);
        orderRepository.save(order);

        PaymentOperation payment = new PaymentOperation();
        payment.setOperationId("IT-PAY-" + order.getOrderId());
        payment.setOrderId(order.getOrderId());
        payment.setOperationType("CHARGE");
        payment.setAmountCents(350);
        payment.setChannel("MOCK");
        payment.setStatus("COMPLETED");
        payment.setIdempotencyKey("it-recon-pay-" + order.getOrderId());
        payment.setUserId(10001L);
        payment.setCreatedAt(now);
        paymentOperationRepository.save(payment);

        var result = reconciliationService.runDaily(100000001L, today, "MOCK");

        assertEquals("MATCHED", result.status());
        assertTrue(result.platformTotal() >= 350);
        assertEquals(result.platformTotal(), result.ledgerTotal());
    }
}
