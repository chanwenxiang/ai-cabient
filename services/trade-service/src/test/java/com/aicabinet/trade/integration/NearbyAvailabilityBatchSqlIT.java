package com.aicabinet.trade.integration;

import com.aicabinet.trade.service.DeviceValidationService;
import java.util.List;
import java.util.Map;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `DeviceValidationService.availableByDevices`（附近柜批量可用性）的**等价性**真库回归。
 *
 * <p>为什么必须真库：这个批量方法替掉的是「逐台 {@code getDeviceStatus(...).available()}」，
 * 而 available 的四个输入（设备行 / ACTIVE 会话 / IN_PROGRESS 且有签到的补货任务 / 锁机）全部来自 SQL 谓词。
 * 单测 mock 掉 mapper 只能证明「我调了哪个方法」，证明不了**两条路径算出同一个答案**——
 * 而附近页把「正被占用」的柜标成可售，是消费者点进去开不了门的那类线上故障。
 *
 * <p>因此本例的判据不是「批量返回了什么」，而是：**对同一批 fixture，批量结果 == 逐台结果**（五种分支全覆盖）。
 * 谁改了 available 的判据（加一个状态、改一个谓词），这里必红——这是它的全部价值。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class NearbyAvailabilityBatchSqlIT {

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
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    private static final String D_IDLE = "DEV-NEAR-IDLE";
    private static final String D_SESSION = "DEV-NEAR-SESSION";
    private static final String D_RESTOCK = "DEV-NEAR-RESTOCK";
    private static final String D_OFFLINE = "DEV-NEAR-OFFLINE";
    private static final String D_LOCKED = "DEV-NEAR-LOCKED";
    private static final long UID = 700777L;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private DeviceValidationService validation;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM shopping_session WHERE device_id LIKE 'DEV-NEAR-%'");
        jdbc.update("DELETE FROM replenishment_task WHERE device_id LIKE 'DEV-NEAR-%'");
        jdbc.update("DELETE FROM device_info WHERE device_id LIKE 'DEV-NEAR-%'");
        jdbc.update("INSERT INTO user_info (user_id, phone_number) VALUES (?, '13900700777') "
                + "ON CONFLICT (user_id) DO NOTHING", UID);

        device(D_IDLE, "ONLINE", false);
        device(D_SESSION, "ONLINE", false);
        device(D_RESTOCK, "ONLINE", false);
        device(D_OFFLINE, "OFFLINE", false);
        device(D_LOCKED, "ONLINE", true);

        jdbc.update("INSERT INTO shopping_session (session_id, user_id, device_id, state) VALUES (?, ?, ?, 'SHOPPING')",
                "S-NEAR-SESSION", UID, D_SESSION);
        jdbc.update("INSERT INTO replenishment_task (device_id, status) VALUES (?, 'IN_PROGRESS')", D_RESTOCK);
        // isRestockingNow = IN_PROGRESS 且已签到；未签到的 IN_PROGRESS 不算占用（另一条腿，不在此例）
        jdbc.update("UPDATE replenishment_task SET check_in_at = NOW() WHERE device_id = ?", D_RESTOCK);
    }

    private void device(String id, String online, boolean locked) {
        // uk_device_name 唯一 ⇒ 名字按 id 派生
        jdbc.update("INSERT INTO device_info (device_id, device_name, device_type, online_status, sales_locked) "
                + "VALUES (?, ?, 'AI_CABINET_V1', ?, ?)", id, "附近批量 IT 柜 " + id, online, locked);
    }

    @Test
    @DisplayName("批量可用性 == 逐台 getDeviceStatus().available()（五种分支逐台比对）")
    void batchMatchesPerDeviceForEveryBranch() {
        List<String> ids = List.of(D_IDLE, D_SESSION, D_RESTOCK, D_OFFLINE, D_LOCKED);
        Map<String, Boolean> batch = validation.availableByDevices(ids);

        for (String id : ids) {
            boolean perDevice = validation.getDeviceStatus(id).available();
            assertEquals(perDevice, batch.get(id),
                    "批量与逐台答案不一致 device=" + id + " ⇒ 附近页会按错误口径标可售/不可售");
        }
        // 显式期望（防止两边一起错：等价性单独成立但语义整体跑偏时这条会红）
        assertTrue(batch.get(D_IDLE), "在线、无会话、无补货、未锁 ⇒ 必须可售");
        assertFalse(batch.get(D_SESSION), "有 ACTIVE 会话 ⇒ 不可售");
        assertFalse(batch.get(D_RESTOCK), "IN_PROGRESS 且已签到的补货 ⇒ 不可售");
        assertFalse(batch.get(D_OFFLINE), "离线 ⇒ 不可售（C-P1）");
        assertFalse(batch.get(D_LOCKED), "已锁机 ⇒ 不可售");
    }

    @Test
    @DisplayName("空入参返回空表；缺失设备行按不可售（不抛、不误标）")
    void emptyInputAndUnknownDeviceAreSafe() {
        assertTrue(validation.availableByDevices(List.of()).isEmpty());
        Map<String, Boolean> m = validation.availableByDevices(List.of("DEV-NEAR-GHOST"));
        assertEquals(Boolean.FALSE, m.get("DEV-NEAR-GHOST"), "设备行缺失必须判不可售，不能默认 true");
    }
}