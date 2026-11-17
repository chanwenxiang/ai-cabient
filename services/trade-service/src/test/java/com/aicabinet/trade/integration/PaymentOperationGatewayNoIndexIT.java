package com.aicabinet.trade.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * V494 真库回归：{@code uk_payment_operation_gateway_trade_no} 只约束**已结算资金行**（COMPLETED），
 * 审计痕迹行允许与资金行共用同一渠道单号。
 *
 * <p>背景（2026-11-17 隔离 UAT 慢渠道真跑抓到的 P0）：V239 的全表唯一索引与 H41/G1/M27 的
 * 「事务外痕迹行」设计天然冲突——
 * <ul>
 *   <li>扣款成功：{@code finalizeChargePending} 要把渠道单号写到 FINALIZED 痕迹行，
 *       而同号的 COMPLETED 资金行已存在 ⇒ 更新撞唯一索引，接口 409、订单停在 PENDING，
 *       但渠道侧 100 分已扣；</li>
 *   <li>渠道退款：REFUND_INITIATED 痕迹行（创建时即写 deterministicRefundNo）与 COMPLETED
 *       退款行共用 out_refund_no（退款重试必须同号查单/重发）⇒ 落资金行同样撞索引。</li>
 * </ul>
 *
 * <p>mock 路径跳过 CHARGE_PENDING 痕迹，单测/IT 从未触达；本 IT 用原生 SQL 钉住三条判据：
 * 痕迹+资金同号可共存、两条 COMPLETED 同号仍被拦。旧索引下前两条必红。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PaymentOperationGatewayNoIndexIT {

    private static final String OP_PREFIX = "IT-GWNO-";

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
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
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanUp() {
        jdbc.update("DELETE FROM payment_operation WHERE operation_id LIKE ?", OP_PREFIX + "%");
    }

    @Test
    @DisplayName("扣款成功：FINALIZED 痕迹行可与同号 COMPLETED 资金行共存（旧索引下 409）")
    void chargeTraceMayShareTradeNoWithSettledRow() {
        String tradeNo = "IT-GWNO-TRADE-1";
        insert(OP_PREFIX + "settled", "CHARGE", "COMPLETED", "IT-GWNO-IDEM-1", tradeNo);

        assertDoesNotThrow(() ->
                insert(OP_PREFIX + "trace", "CHARGE", "FINALIZED", "IT-GWNO-IDEM-2", tradeNo));
        assertEquals(2, countByTradeNo(tradeNo));
    }

    @Test
    @DisplayName("退款：REFUND_INITIATED 痕迹行可与同号 COMPLETED 退款行共存（重试须复用 out_refund_no）")
    void refundTraceMayShareOutRefundNoWithSettledRow() {
        String outRefundNo = "IT-GWNO-REFUND-1";
        insert(OP_PREFIX + "refund-trace", "REFUND", "REFUND_INITIATED", "IT-GWNO-IDEM-3", outRefundNo);

        assertDoesNotThrow(() ->
                insert(OP_PREFIX + "refund-settled", "REFUND", "COMPLETED", "IT-GWNO-IDEM-4", outRefundNo));
        assertEquals(2, countByTradeNo(outRefundNo));
    }

    @Test
    @DisplayName("资金唯一性仍守住：两条 COMPLETED 流水共用同一渠道单号必须被唯一索引拦下")
    void twoSettledRowsWithSameTradeNoStillRejected() {
        String tradeNo = "IT-GWNO-TRADE-2";
        insert(OP_PREFIX + "settled-a", "CHARGE", "COMPLETED", "IT-GWNO-IDEM-5", tradeNo);

        assertThrows(DataIntegrityViolationException.class, () ->
                insert(OP_PREFIX + "settled-b", "CHARGE", "COMPLETED", "IT-GWNO-IDEM-6", tradeNo));
    }

    private void insert(String operationId, String type, String status, String idemKey, String gatewayTradeNo) {
        jdbc.update("""
                INSERT INTO payment_operation
                    (operation_id, order_id, operation_type, amount_cents, channel, status,
                     idempotency_key, gateway_trade_no, created_at, updated_at)
                VALUES (?, NULL, ?, 100, 'WECHAT', ?, ?, ?, now(), now())
                """, operationId, type, status, idemKey, gatewayTradeNo);
    }

    private int countByTradeNo(String gatewayTradeNo) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payment_operation WHERE gateway_trade_no = ?",
                Integer.class, gatewayTradeNo);
        return count == null ? 0 : count;
    }
}
