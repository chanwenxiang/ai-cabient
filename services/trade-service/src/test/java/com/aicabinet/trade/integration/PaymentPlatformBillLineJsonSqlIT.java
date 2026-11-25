package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.PaymentPlatformBillLine;
import com.aicabinet.trade.mapper.PaymentPlatformBillLineMapper;
import com.aicabinet.trade.reconciliation.PlatformBillLine;
import com.aicabinet.trade.reconciliation.WeChatBillCsvParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对账明细 raw_detail 是 JSONB；账单解析器必须输出合法 JSON，不能把 CSV 原文直接塞库。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PaymentPlatformBillLineJsonSqlIT {

    private static final String CHANNEL = "IT_JSON";
    private static final String TRADE_NO = "IT-JSON-4200001234";
    private static final LocalDate BILL_DATE = LocalDate.of(2024, 1, 15);

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

    @Autowired
    private PaymentPlatformBillLineMapper mapper;

    @BeforeEach
    void resetRows() {
        jdbc.update("DELETE FROM payment_platform_bill_line WHERE platform_trade_no LIKE ?", "IT-JSON-%");
        jdbc.update("DELETE FROM payment_reconciliation WHERE channel = ?", CHANNEL);
    }

    @Test
    @DisplayName("微信账单 CSV 原文序列化为 JSON 字符串后可写 JSONB，并按原文读回")
    void wechatRawDetailRoundTripsAsJsonString() throws Exception {
        String[] cols = new String[25];
        Arrays.fill(cols, "");
        cols[5] = TRADE_NO;
        cols[6] = "IT-JSON-ORDER";
        cols[12] = "3.50";
        String rawLine = "`" + String.join(",`", cols) + "`";
        String expected = rawLine;
        String csv = "交易时间,微信订单号,商户订单号,...\n" + rawLine;

        List<PlatformBillLine> parsed = WeChatBillCsvParser.parse(csv, BILL_DATE);
        assertEquals(1, parsed.size());
        long reconId = insertRecon();
        PaymentPlatformBillLine entity = entityOf(reconId, parsed.get(0));
        mapper.insert(entity);

        String jsonType = jdbc.queryForObject(
                "SELECT jsonb_typeof(raw_detail) FROM payment_platform_bill_line WHERE line_id = ?",
                String.class, entity.getLineId());
        String rawText = jdbc.queryForObject(
                "SELECT raw_detail #>> '{}' FROM payment_platform_bill_line WHERE line_id = ?",
                String.class, entity.getLineId());
        assertEquals("string", jsonType);
        assertEquals(expected, rawText);

        PaymentPlatformBillLine saved = mapper.selectById(entity.getLineId());
        assertEquals(parsed.get(0).rawDetail(), saved.getRawDetail());
        assertEquals(expected, new ObjectMapper().readTree(saved.getRawDetail()).asText());
        assertTrue(saved.getRawDetail().startsWith("\""), "JSONB 读回必须是合法 JSON 字符串");
    }

    private long insertRecon() {
        Long id = jdbc.queryForObject(
                "INSERT INTO payment_reconciliation (recon_date, channel, status, detail) "
                        + "VALUES (?, ?, 'EMPTY', '{}'::jsonb) RETURNING recon_id",
                Long.class, BILL_DATE, CHANNEL);
        return id == null ? -1L : id;
    }

    private PaymentPlatformBillLine entityOf(long reconId, PlatformBillLine line) {
        PaymentPlatformBillLine entity = new PaymentPlatformBillLine();
        entity.setReconId(reconId);
        entity.setChannel("WECHAT");
        entity.setPlatformTradeNo(line.platformTradeNo());
        entity.setMerchantOrderNo(line.merchantOrderNo());
        entity.setAmountCents(line.amountCents());
        entity.setTradeTime(line.tradeTime());
        entity.setTradeType(line.tradeType());
        entity.setMatched(true);
        entity.setRawDetail(line.rawDetail());
        return entity;
    }
}
