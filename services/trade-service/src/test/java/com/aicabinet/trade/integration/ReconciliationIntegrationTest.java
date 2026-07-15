package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.service.ReconciliationService;
import com.aicabinet.common.enums.SessionState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class ReconciliationIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private CabinetOrderMapper orderRepository;

    @Autowired
    private ShoppingSessionMapper sessionRepository;

    @Test
    void mockReconciliation_matchesInsertedOrder() {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
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
        orderRepository.save(order);

        var result = reconciliationService.runDaily(100000001L, today, "MOCK");

        assertEquals("MATCHED", result.status());
        assertTrue(result.platformTotal() >= 350);
        assertEquals(result.platformTotal(), result.ledgerTotal());
    }
}
